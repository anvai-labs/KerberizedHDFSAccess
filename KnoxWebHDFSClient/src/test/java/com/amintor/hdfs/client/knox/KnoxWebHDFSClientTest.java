package com.amintor.hdfs.client.knox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnoxWebHDFSClientTest {
  private static final URI TOPOLOGY =
      URI.create("https://gateway.example.com:8443/gateway/cdp-proxy");

  @Test
  void rejectsMissingCommandLineArgumentsBeforeReadingCredentials() {
    assertThrows(IllegalArgumentException.class, () -> KnoxWebHDFSClient.main(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> KnoxWebHDFSClient.main(new String[] {TOPOLOGY.toString()}));
  }

  @Test
  void sendsTokenOnlyInAuthorizationHeaderAndPrintsSuccessfulResponse() throws IOException {
    AtomicReference<URI> requestedUri = new AtomicReference<>();
    AtomicReference<String> transmittedToken = new AtomicReference<>();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    KnoxWebHDFSClient.listStatus(
        TOPOLOGY,
        "/user/alice/quarterly report",
        "short-lived-token",
        new PrintStream(bytes, true, StandardCharsets.UTF_8),
        (uri, token) -> {
          requestedUri.set(uri);
          transmittedToken.set(token);
          return new KnoxWebHDFSClient.Response(200, "{\"FileStatuses\":{}}");
        });

    assertEquals("https", requestedUri.get().getScheme());
    assertEquals(
        "/gateway/cdp-proxy/webhdfs/v1/user/alice/quarterly report",
        requestedUri.get().getPath());
    assertEquals("op=LISTSTATUS", requestedUri.get().getQuery());
    assertFalse(requestedUri.get().toString().contains("short-lived-token"));
    assertEquals("short-lived-token", transmittedToken.get());
    assertEquals("{\"FileStatuses\":{}}\n", bytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void rejectsInsecureOrAmbiguousTopologyUrls() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            KnoxWebHDFSClient.listStatusUri(
                URI.create("http://gateway.example.com/gateway"), "/"));
    assertThrows(
        IllegalArgumentException.class,
        () -> KnoxWebHDFSClient.listStatusUri(URI.create("https:///gateway"), "/"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            KnoxWebHDFSClient.listStatusUri(
                URI.create("https://user:password@gateway.example.com/gateway"), "/"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            KnoxWebHDFSClient.listStatusUri(
                URI.create("https://gateway.example.com/gateway?token=unsafe"), "/"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            KnoxWebHDFSClient.listStatusUri(
                URI.create("https://gateway.example.com/gateway#fragment"), "/"));
  }

  @Test
  void rejectsUnsafePathsAndTokens() {
    assertThrows(
        IllegalArgumentException.class, () -> KnoxWebHDFSClient.listStatusUri(TOPOLOGY, " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> KnoxWebHDFSClient.listStatusUri(TOPOLOGY, "/user/../admin"));
    assertThrows(
        IllegalArgumentException.class,
        () -> KnoxWebHDFSClient.listStatusUri(TOPOLOGY, "/user\\admin"));
    assertThrows(IllegalArgumentException.class, () -> KnoxWebHDFSClient.validateToken(null));
    assertThrows(IllegalArgumentException.class, () -> KnoxWebHDFSClient.validateToken("  "));
    assertThrows(
        IllegalArgumentException.class,
        () -> KnoxWebHDFSClient.validateToken("token\r\nInjected: value"));

    assertEquals(
        "/gateway/cdp-proxy/webhdfs/v1/user/alice",
        KnoxWebHDFSClient.listStatusUri(
                URI.create("https://gateway.example.com/gateway/cdp-proxy/"), "user/alice")
            .getPath());
  }

  @Test
  void omitsTokenAndResponseBodyFromFailure() {
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                KnoxWebHDFSClient.listStatus(
                    TOPOLOGY,
                    "/user/alice",
                    "secret-token",
                    System.out,
                    (uri, token) -> new KnoxWebHDFSClient.Response(401, "sensitive IdP response")));

    assertEquals("Knox WebHDFS request failed with HTTP 401", failure.getMessage());
    assertFalse(failure.getMessage().contains("secret-token"));
    assertFalse(failure.getMessage().contains("sensitive IdP response"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdkTransportBuildsAGetRequestWithSecurityHeaders() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> httpResponse = mock(HttpResponse.class);
    when(httpResponse.statusCode()).thenReturn(200);
    when(httpResponse.body()).thenReturn("{}");
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(httpResponse);
    ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);

    KnoxWebHDFSClient.Response response =
        new KnoxWebHDFSClient.JdkTransport(client)
            .get(URI.create("https://gateway.example.com/webhdfs/v1/?op=LISTSTATUS"), "token");

    org.mockito.Mockito.verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertEquals("GET", request.getValue().method());
    assertEquals(
        "Bearer token",
        request.getValue().headers().firstValue("Authorization").orElseThrow());
    assertEquals(
        "application/json", request.getValue().headers().firstValue("Accept").orElseThrow());
    assertEquals(200, response.statusCode());
    assertEquals("{}", response.body());
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdkTransportPreservesInterruptionAsAnIoFailure() throws Exception {
    HttpClient client = mock(HttpClient.class);
    when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new InterruptedException("stop"));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                new KnoxWebHDFSClient.JdkTransport(client)
                    .get(URI.create("https://gateway.example.com/webhdfs/v1/?op=LISTSTATUS"), "token"));

    assertTrue(Thread.currentThread().isInterrupted());
    assertEquals("Interrupted while calling Knox WebHDFS", failure.getMessage());
    Thread.interrupted();
  }
}
