package com.plastiflow.integration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class OpenAiProviderTest {
  final ObjectMapper json = new ObjectMapper();

  OpenAiProvider provider() {
    return new OpenAiProvider(
        json,
        false,
        "",
        "",
        HttpClient.newHttpClient(),
        URI.create("https://api.openai.com/v1/responses"));
  }

  @Test
  void schemaRequestUsesResponsesAndDisablesStoredResponse() throws Exception {
    var p = provider();
    var body = json.valueToTree(p.body(json.readTree("{\"products\":[]}")));
    assertThat(body.path("store").asBoolean()).isFalse();
    assertThat(body.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
    assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();
    assertThat(p.enabled()).isFalse();
  }

  @Test
  void validCompletedOutputParsesAndRefusalIsRejected() throws Exception {
    var p = provider();
    var payload =
        Map.of(
            "status",
            "completed",
            "output",
            List.of(
                Map.of(
                    "type",
                    "message",
                    "content",
                    List.of(
                        Map.of(
                            "type",
                            "output_text",
                            "text",
                            "{\"summary\":\"Example\",\"suggestions\":[]}")))));
    assertThat(p.parse(json.valueToTree(payload)).path("summary").asText()).isEqualTo("Example");
    assertThatThrownBy(
            () ->
                p.parse(
                    json.readTree(
                        "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"blocked\"}]}]}")))
        .hasMessage("AI_REFUSED");
    assertThatThrownBy(() -> p.parse(json.readTree("{\"status\":\"incomplete\"}")))
        .hasMessage("AI_INCOMPLETE");
  }

  @Test
  void localHttpContractSendsBoundedRequestAndParsesResponse() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var captured = new CompletableFuture<JsonNode>();
    server.createContext(
        "/responses",
        exchange -> {
          try {
            captured.complete(json.readTree(exchange.getRequestBody()));
            byte[] bytes =
                json.writeValueAsBytes(
                    Map.of(
                        "status",
                        "completed",
                        "output",
                        List.of(
                            Map.of(
                                "type",
                                "message",
                                "content",
                                List.of(
                                    Map.of(
                                        "type",
                                        "output_text",
                                        "text",
                                        "{\"summary\":\"Local fixture\",\"suggestions\":[]}"))))));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var p =
          new OpenAiProvider(
              json,
              true,
              "fixture-key",
              "fixture-model",
              HttpClient.newHttpClient(),
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"));
      assertThat(p.generate(json.readTree("{\"products\":[]}")).path("summary").asText())
          .isEqualTo("Local fixture");
      assertThat(captured.get(1, TimeUnit.SECONDS).path("model").asText())
          .isEqualTo("fixture-model");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void oversizedResponseCancelsBody() throws Exception {
    var body = new OpenAiProvider.BoundedBody();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    body.onSubscribe(
        new Flow.Subscription() {
          public void request(long count) {}

          public void cancel() {
            cancelled.set(true);
          }
        });
    body.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[131073])));
    assertThat(cancelled.get()).isTrue();
    assertThat(body.getBody().toCompletableFuture().isCompletedExceptionally()).isTrue();
  }
}
