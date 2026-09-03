package com.amintor.hdfs.client.knox;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/** Lists an HDFS directory through an HTTPS-only Apache Knox WebHDFS endpoint. */
public final class KnoxWebHDFSClient {
  static final String TOKEN_ENVIRONMENT_VARIABLE = "KNOX_BEARER_TOKEN";

  private KnoxWebHDFSClient() {}

  /** Usage: {@code <knox-topology-base-url> <hdfs-path>}. */
  public static void main(String[] args) throws IOException {
    if (args == null || args.length != 2) {
      throw new IllegalArgumentException("Usage: <knox-topology-base-url> <hdfs-path>");
    }
    String token = System.getenv(TOKEN_ENVIRONMENT_VARIABLE);
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    listStatus(URI.create(args[0]), args[1], token, System.out, new JdkTransport(client));
  }

  static void listStatus(
      URI topologyBase,
      String hdfsPath,
      String bearerToken,
      PrintStream output,
      Transport transport)
      throws IOException {
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(transport, "transport");
    validateToken(bearerToken);
    URI requestUri = listStatusUri(topologyBase, hdfsPath);
    Response response = transport.get(requestUri, bearerToken);
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Knox WebHDFS request failed with HTTP " + response.statusCode());
    }
    output.println(response.body());
  }

  static URI listStatusUri(URI topologyBase, String hdfsPath) {
    Objects.requireNonNull(topologyBase, "topologyBase");
    if (!"https".equalsIgnoreCase(topologyBase.getScheme()) || topologyBase.getHost() == null) {
      throw new IllegalArgumentException("Knox topology URL must use HTTPS and include a host");
    }
    if (topologyBase.getUserInfo() != null
        || topologyBase.getQuery() != null
        || topologyBase.getFragment() != null) {
      throw new IllegalArgumentException(
          "Knox topology URL must not contain credentials, a query, or a fragment");
    }
    if (hdfsPath == null || hdfsPath.isBlank()) {
      throw new IllegalArgumentException("HDFS path must not be blank");
    }
    for (String segment : hdfsPath.split("/")) {
      if (".".equals(segment) || "..".equals(segment)) {
        throw new IllegalArgumentException("HDFS path must not contain traversal segments");
      }
    }
    if (hdfsPath.chars()
        .anyMatch(character -> Character.isISOControl(character) || character == '\\')) {
      throw new IllegalArgumentException("HDFS path contains an unsafe character");
    }

    String basePath = topologyBase.getPath();
    if (basePath.endsWith("/")) {
      basePath = basePath.substring(0, basePath.length() - 1);
    }
    String absoluteHdfsPath = hdfsPath.startsWith("/") ? hdfsPath : "/" + hdfsPath;
    try {
      return new URI(
          "https",
          topologyBase.getRawAuthority(),
          basePath + "/webhdfs/v1" + absoluteHdfsPath,
          "op=LISTSTATUS",
          null);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Invalid Knox topology URL or HDFS path", exception);
    }
  }

  static void validateToken(String token) {
    if (token == null || token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException(
          TOKEN_ENVIRONMENT_VARIABLE + " must contain one non-whitespace bearer token");
    }
  }

  @FunctionalInterface
  interface Transport {
    Response get(URI uri, String bearerToken) throws IOException;
  }

  record Response(int statusCode, String body) {
    Response {
      Objects.requireNonNull(body, "body");
    }
  }

  static final class JdkTransport implements Transport {
    private final HttpClient client;

    JdkTransport(HttpClient client) {
      this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Response get(URI uri, String bearerToken) throws IOException {
      HttpRequest request =
          HttpRequest.newBuilder(uri)
              .timeout(Duration.ofSeconds(30))
              .header("Accept", "application/json")
              .header("Authorization", "Bearer " + bearerToken)
              .GET()
              .build();
      try {
        HttpResponse<String> response =
            client.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while calling Knox WebHDFS", exception);
      }
    }
  }
}
