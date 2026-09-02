package com.amintor.hdfs.client.kerberizedhdfsclient;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;

/** Lists an HDFS directory after authenticating with a Kerberos principal and keytab. */
public final class KerberizedHDFSClient {

  static final Path DEFAULT_CORE_SITE = Paths.get("/etc/hadoop/config/client/core-site.xml");
  static final Path DEFAULT_HDFS_SITE = Paths.get("/etc/hadoop/config/client/hdfs-site.xml");

  private KerberizedHDFSClient() {}

  /** Usage: {@code <hdfs-path> <principal> <keytab> [<core-site.xml> <hdfs-site.xml>]}. */
  public static void main(String[] args) throws IOException {
    Arguments arguments = Arguments.parse(args);
    Configuration configuration = loadConfiguration(arguments.coreSite, arguments.hdfsSite);

    listDirectory(
        configuration,
        arguments.principal,
        arguments.keytab,
        new org.apache.hadoop.fs.Path(arguments.hdfsPath),
        System.out,
        KerberizedHDFSClient::login,
        conf -> new HadoopClient(FileSystem.get(conf)));
  }

  static Configuration loadConfiguration(Path coreSite, Path hdfsSite) throws IOException {
    requireReadableFile(coreSite, "core-site.xml");
    requireReadableFile(hdfsSite, "hdfs-site.xml");

    Configuration configuration = new Configuration(false);
    configuration.addResource(coreSite.toUri().toURL());
    configuration.addResource(hdfsSite.toUri().toURL());
    // Force parsing here so malformed configuration fails before authentication.
    configuration.size();
    return configuration;
  }

  static void listDirectory(
      Configuration configuration,
      String principal,
      Path keytab,
      org.apache.hadoop.fs.Path hdfsPath,
      PrintStream output,
      Authenticator authenticator,
      ClientFactory clientFactory)
      throws IOException {
    Objects.requireNonNull(configuration, "configuration");
    Objects.requireNonNull(hdfsPath, "hdfsPath");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(authenticator, "authenticator");
    Objects.requireNonNull(clientFactory, "clientFactory");

    String authentication = configuration.getTrimmed("hadoop.security.authentication", "");
    if (!"kerberos".equalsIgnoreCase(authentication)) {
      throw new IllegalArgumentException(
          "hadoop.security.authentication must be set to kerberos");
    }
    if (principal == null || principal.trim().isEmpty()) {
      throw new IllegalArgumentException("principal must not be blank");
    }
    requireReadableFile(keytab, "keytab");

    authenticator.login(configuration, principal, keytab);
    try (HdfsClient client = clientFactory.open(configuration)) {
      for (FileStatus status : client.listStatus(hdfsPath)) {
        output.printf(
            "Replication:%d\tOwner:%s\tGroup:%s\tPath:%s%n",
            status.getReplication(), status.getOwner(), status.getGroup(), status.getPath());
      }
    }
  }

  private static void login(Configuration configuration, String principal, Path keytab)
      throws IOException {
    UserGroupInformation.setConfiguration(configuration);
    UserGroupInformation.loginUserFromKeytab(principal, keytab.toString());
  }

  private static void requireReadableFile(Path path, String description) throws IOException {
    if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
      throw new IOException(description + " is not a readable regular file: " + path);
    }
  }

  @FunctionalInterface
  interface Authenticator {
    void login(Configuration configuration, String principal, Path keytab) throws IOException;
  }

  @FunctionalInterface
  interface ClientFactory {
    HdfsClient open(Configuration configuration) throws IOException;
  }

  interface HdfsClient extends AutoCloseable {
    FileStatus[] listStatus(org.apache.hadoop.fs.Path path) throws IOException;

    @Override
    void close() throws IOException;
  }

  static final class HadoopClient implements HdfsClient {
    private final FileSystem fileSystem;

    HadoopClient(FileSystem fileSystem) {
      this.fileSystem = fileSystem;
    }

    @Override
    public FileStatus[] listStatus(org.apache.hadoop.fs.Path path) throws IOException {
      return fileSystem.listStatus(path);
    }

    @Override
    public void close() throws IOException {
      fileSystem.close();
    }
  }

  static final class Arguments {
    final String hdfsPath;
    final String principal;
    final Path keytab;
    final Path coreSite;
    final Path hdfsSite;

    private Arguments(
        String hdfsPath, String principal, Path keytab, Path coreSite, Path hdfsSite) {
      this.hdfsPath = hdfsPath;
      this.principal = principal;
      this.keytab = keytab;
      this.coreSite = coreSite;
      this.hdfsSite = hdfsSite;
    }

    static Arguments parse(String[] args) {
      if (args == null || (args.length != 3 && args.length != 5)) {
        throw new IllegalArgumentException(
            "Usage: <hdfs-path> <principal> <keytab> [<core-site.xml> <hdfs-site.xml>]");
      }
      Path coreSite = args.length == 5 ? Paths.get(args[3]) : DEFAULT_CORE_SITE;
      Path hdfsSite = args.length == 5 ? Paths.get(args[4]) : DEFAULT_HDFS_SITE;
      return new Arguments(args[0], args[1], Paths.get(args[2]), coreSite, hdfsSite);
    }
  }
}
