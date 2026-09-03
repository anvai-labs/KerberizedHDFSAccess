# Kerberized HDFS Access

Secure Java examples for two deliberately separate HDFS access boundaries:

- `KerberizedHDFSClient` uses Kerberos for native HDFS RPC.
- `KnoxWebHDFSClient` uses an OIDC/OAuth bearer token over mandatory HTTPS through Apache Knox's
  WebHDFS gateway. Knox validates the token and bridges the request to the secured Hadoop cluster.

| Access boundary | Supported example | Authentication model |
| --- | --- | --- |
| Native HDFS RPC | `KerberizedHDFSClient` | Kerberos principal and keytab |
| Knox WebHDFS REST | `KnoxWebHDFSClient` | Short-lived OAuth/OIDC bearer token (typically a JWT) |
| Interactive enterprise SSO | Knox/KnoxSSO configuration, outside this CLI | SAML or OIDC at the IdP; Knox bridges the authenticated identity |

Cloudera documents the [Knox Token API](https://docs.cloudera.com/cdp-private-cloud-base/7.3.2/knox-authentication/topics/security-knox-token-api.html)
and its [LDAP, Active Directory, SSO, and SAML authentication options](https://docs.cloudera.com/cdp-private-cloud-base/7.3.2/knox-authentication/security-knox-authentication.pdf).
The examples keep gateway-token access separate from native Kerberos so neither mechanism is
misrepresented as a drop-in replacement for the other.

## Requirements

- Java 17 or 21
- Maven 3.9+
- Kerberos credentials and Hadoop client configuration compatible with the target cluster

Two pinned compatibility profiles are supported:

- `apache-upstream` (default): Apache Hadoop 3.5.0 on Java 17 and 21.
- `cdp-7.3.2-sp1`: Cloudera Runtime 7.3.2 SP1 Hadoop
  `3.4.2.7.3.2.10000-317` on Java 17.

Hadoop dependencies use `provided` scope because the cluster supplies the runtime. Do not commit
keytabs, access tokens, client secrets, or generated Hadoop configuration.

## Build and test

```bash
./mvnw -B -ntp -Papache-upstream clean verify
./mvnw -B -ntp -Pcdp-7.3.2-sp1 clean verify
```

The build fails when there are no tests and enforces at least 80% line and 70% branch coverage in
each module. HTML reports are written below each module's `target/site/jacoco/` directory.

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

## Knox WebHDFS with OIDC/OAuth

Obtain a short-lived access token from the identity provider configured for Knox, expose it only to
the process, and supply the Knox topology base URL (not a direct NameNode URL):

```bash
export KNOX_BEARER_TOKEN='<short-lived-access-token>'
java -cp KnoxWebHDFSClient/target/knox-webhdfs-client-1.0-SNAPSHOT.jar \
  com.amintor.hdfs.client.knox.KnoxWebHDFSClient \
  https://gateway.example.com:8443/gateway/cdp-proxy /user/alice
```

The example refuses plaintext HTTP, URLs containing embedded credentials, blank or whitespace-
containing tokens, and non-success responses. It never places the bearer token in the URI or an
error message. Interactive SSO or SAML login happens at the configured identity provider/Knox SSO
flow; this non-interactive client consumes the resulting short-lived OIDC/OAuth access token.
