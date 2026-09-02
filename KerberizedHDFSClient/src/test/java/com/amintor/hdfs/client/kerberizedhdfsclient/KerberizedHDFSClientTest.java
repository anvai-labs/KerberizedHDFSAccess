package com.amintor.hdfs.client.kerberizedhdfsclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KerberizedHDFSClientTest {

  @TempDir Path temporaryDirectory;

  @Test
  void parsesDefaultAndExplicitConfigurationPaths() {
    KerberizedHDFSClient.Arguments defaults =
        KerberizedHDFSClient.Arguments.parse(
            new String[] {"/user", "user@EXAMPLE.COM", "/tmp/user.keytab"});
    assertEquals(KerberizedHDFSClient.DEFAULT_CORE_SITE, defaults.coreSite);
    assertEquals(KerberizedHDFSClient.DEFAULT_HDFS_SITE, defaults.hdfsSite);

    KerberizedHDFSClient.Arguments explicit =
        KerberizedHDFSClient.Arguments.parse(
            new String[] {
              "/user", "user@EXAMPLE.COM", "/tmp/user.keytab", "core.xml", "hdfs.xml"
            });
    assertEquals(Path.of("core.xml"), explicit.coreSite);
    assertEquals(Path.of("hdfs.xml"), explicit.hdfsSite);
  }

  @Test
  void rejectsIncompleteArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> KerberizedHDFSClient.Arguments.parse(new String[] {"/user"}));
    assertThrows(IllegalArgumentException.class, () -> KerberizedHDFSClient.Arguments.parse(null));
  }

  @Test
  void loadsBothConfigurationFiles() throws IOException {
    Path core = writeConfiguration("fs.defaultFS", "hdfs://namenode.example:8020", "core.xml");
    Path hdfs = writeConfiguration("hadoop.security.authentication", "kerberos", "hdfs.xml");

    Configuration configuration = KerberizedHDFSClient.loadConfiguration(core, hdfs);

    assertEquals("hdfs://namenode.example:8020", configuration.get("fs.defaultFS"));
    assertEquals("kerberos", configuration.get("hadoop.security.authentication"));
  }

  @Test
  void rejectsMissingConfigurationFile() {
    Path missing = temporaryDirectory.resolve("missing.xml");
    assertThrows(
        IOException.class, () -> KerberizedHDFSClient.loadConfiguration(missing, missing));
  }

  @Test
  void rejectsUnsafeAuthenticationAndCredentials() throws IOException {
    Configuration simple = new Configuration(false);
    simple.set("hadoop.security.authentication", "simple");
    Path keytab = Files.createFile(temporaryDirectory.resolve("user.keytab"));

    assertThrows(
        IllegalArgumentException.class,
        () -> execute(simple, "user@EXAMPLE.COM", keytab, new FakeClient(new FileStatus[0])));

    Configuration kerberos = kerberosConfiguration();
    assertThrows(
        IllegalArgumentException.class,
        () -> execute(kerberos, "  ", keytab, new FakeClient(new FileStatus[0])));
    assertThrows(
        IOException.class,
        () ->
            execute(
                kerberos,
                "user@EXAMPLE.COM",
                temporaryDirectory.resolve("missing.keytab"),
                new FakeClient(new FileStatus[0])));
  }

  @Test
  void authenticatesListsAndClosesWithoutExposingKeytab() throws IOException {
    Path keytab = Files.createFile(temporaryDirectory.resolve("user.keytab"));
    org.apache.hadoop.fs.Path listedPath = new org.apache.hadoop.fs.Path("/user");
    FileStatus status =
        new FileStatus(
            10,
            false,
            3,
            128,
            1,
            1,
            null,
            "alice",
            "analytics",
            new org.apache.hadoop.fs.Path("hdfs://namenode.example:8020/user/data"));
    FakeClient client = new FakeClient(new FileStatus[] {status});
    AtomicReference<String> authenticatedPrincipal = new AtomicReference<>();
    AtomicReference<Path> authenticatedKeytab = new AtomicReference<>();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    KerberizedHDFSClient.listDirectory(
        kerberosConfiguration(),
        "alice@EXAMPLE.COM",
        keytab,
        listedPath,
        new PrintStream(bytes, true, StandardCharsets.UTF_8),
        (configuration, principal, path) -> {
          authenticatedPrincipal.set(principal);
          authenticatedKeytab.set(path);
        },
        configuration -> client);

    String output = bytes.toString(StandardCharsets.UTF_8);
    assertEquals("alice@EXAMPLE.COM", authenticatedPrincipal.get());
    assertEquals(keytab, authenticatedKeytab.get());
    assertEquals(listedPath, client.requestedPath);
    assertTrue(client.closed.get());
    assertTrue(output.contains("Replication:3\tOwner:alice\tGroup:analytics"));
    assertFalse(output.contains(keytab.toString()));
  }

  @Test
  void closesClientWhenListingFails() throws IOException {
    Path keytab = Files.createFile(temporaryDirectory.resolve("failing.keytab"));
    FakeClient client = new FakeClient(new IOException("listing failed"));

    IOException failure =
        assertThrows(
            IOException.class,
            () -> execute(kerberosConfiguration(), "user@EXAMPLE.COM", keytab, client));

    assertEquals("listing failed", failure.getMessage());
    assertTrue(client.closed.get());
  }

  @Test
  void productionAdapterDelegatesToHadoopFileSystem() throws IOException {
    Path file = Files.writeString(temporaryDirectory.resolve("data.txt"), "data");
    FileSystem localFileSystem = FileSystem.getLocal(new Configuration());
    KerberizedHDFSClient.HadoopClient client =
        new KerberizedHDFSClient.HadoopClient(localFileSystem);

    FileStatus[] statuses =
        client.listStatus(new org.apache.hadoop.fs.Path(file.toUri()));

    assertEquals(1, statuses.length);
    assertEquals(4, statuses[0].getLen());
    client.close();
  }

  private void execute(
      Configuration configuration, String principal, Path keytab, FakeClient client)
      throws IOException {
    KerberizedHDFSClient.listDirectory(
        configuration,
        principal,
        keytab,
        new org.apache.hadoop.fs.Path("/user"),
        new PrintStream(new ByteArrayOutputStream()),
        (ignoredConfiguration, ignoredPrincipal, ignoredKeytab) -> {},
        ignoredConfiguration -> client);
  }

  private Configuration kerberosConfiguration() {
    Configuration configuration = new Configuration(false);
    configuration.set("hadoop.security.authentication", "kerberos");
    return configuration;
  }

  private Path writeConfiguration(String key, String value, String fileName) throws IOException {
    Path file = temporaryDirectory.resolve(fileName);
    Files.writeString(
        file,
        "<?xml version=\"1.0\"?><configuration><property><name>"
            + key
            + "</name><value>"
            + value
            + "</value></property></configuration>",
        StandardCharsets.UTF_8);
    return file;
  }

  private static final class FakeClient implements KerberizedHDFSClient.HdfsClient {
    private final FileStatus[] statuses;
    private final IOException failure;
    private final AtomicBoolean closed = new AtomicBoolean();
    private org.apache.hadoop.fs.Path requestedPath;

    private FakeClient(FileStatus[] statuses) {
      this.statuses = statuses;
      this.failure = null;
    }

    private FakeClient(IOException failure) {
      this.statuses = null;
      this.failure = failure;
    }

    @Override
    public FileStatus[] listStatus(org.apache.hadoop.fs.Path path) throws IOException {
      requestedPath = path;
      if (failure != null) {
        throw failure;
      }
      return statuses;
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }
}
