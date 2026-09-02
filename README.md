# Kerberized HDFS Access

A small Java client that authenticates with a Kerberos principal/keytab and lists an HDFS directory.

## Requirements

- Java 17 or 21
- Maven 3.9+
- Kerberos credentials and Hadoop client configuration compatible with the target cluster

The build uses Apache Hadoop 3.5.0. If a vendor-managed cluster requires a different client version,
change `hadoop.version` in the root `pom.xml` to the vendor-supported version and rerun the full test
matrix. Do not commit keytabs or credentials.

## Build and test

```bash
mvn -B -ntp clean verify
```

The build fails when there are no tests and enforces at least 75% line coverage. The HTML coverage
report is written to `KerberizedHDFSClient/target/site/jacoco/`.

## Run

```bash
java -cp "KerberizedHDFSClient/target/kerberized-hdfs-client-1.0-SNAPSHOT.jar:<hadoop-client-classpath>" \
  com.amintor.hdfs.client.kerberizedhdfsclient.KerberizedHDFSClient \
  /user/ user@EXAMPLE.COM /secure/path/user.keytab
```

By default, the client reads:

- `/etc/hadoop/config/client/core-site.xml`
- `/etc/hadoop/config/client/hdfs-site.xml`

Pass both configuration paths after the keytab to override those defaults:

```text
<hdfs-path> <principal> <keytab> <core-site.xml> <hdfs-site.xml>
```

The client rejects non-Kerberos configuration, unreadable/non-regular keytabs, and malformed or
missing Hadoop configuration before opening an HDFS connection.
