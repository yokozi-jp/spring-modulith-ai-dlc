package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.testkit.CapturedSpans;
import com.example.demo.testkit.SharedTestConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 送信の HTTP の span に、クエリ、ヘッダー、body の秘密が入らないことを、実際のポートへの呼び出しで検証する。
 *
 * <p>Boot の {@link RestClient.Builder} から作った client が自分の API を呼ぶ。送信の span の属性は、Spring が既定で {@code
 * http.url} と {@code uri} にクエリを入れるので、{@link ClientObservationConfig} が落とすことを確かめる。
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      // テストの既定は tracing の export を無効にする。
      "spring.test.tracing.export=true",
      "management.tracing.export.otlp.enabled=false"
    })
@Import(SharedTestConfiguration.class)
class ClientSpanContractTest {

  /** 送信の span の、URL の属性（Spring の既定の名前）。 */
  private static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");

  /** 認証が要るので 401 になる API。応答の status は問わない。 */
  private static final String PATH = "/api/v1/orders";

  /** 終えた span。 */
  @Autowired private CapturedSpans capturedSpans;

  /** Boot が observation を入れる client の builder。 */
  @Autowired private RestClient.Builder restClientBuilder;

  /** 起動したサーバーのポート。 */
  @LocalServerPort private int port;

  @Test
  @DisplayName("送信の span は、クエリ、ヘッダー、body の秘密を属性、event、status に持たない")
  void clientSpanCarriesNoSecret() {
    final String secret = "S3CRET" + UUID.randomUUID();
    final String url = "http://localhost:" + port + PATH;

    restClientBuilder
        .build()
        .post()
        .uri(url + "?api_key=" + secret + "#" + secret)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
        .header(HttpHeaders.COOKIE, "SESSION=" + secret)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"password\":\"" + secret + "\"}")
        .exchange((request, response) -> response.getStatusCode());

    final List<SpanData> clientSpans =
        capturedSpans.all().stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
    assertTrue(
        clientSpans.stream().anyMatch(span -> url.equals(span.getAttributes().get(HTTP_URL))),
        () -> "クエリを落とした http.url を持つ送信の span がない: " + clientSpans);
    final List<SpanData> leaking =
        capturedSpans.all().stream().filter(span -> renderedText(span).contains(secret)).toList();
    assertEquals(List.of(), leaking, "秘密を持つ span");
    assertFalse(
        capturedSpans.all().stream()
            .anyMatch(span -> span.getName().startsWith("security filterchain")),
        "security filterchain の span がある");
  }

  /** span の名前、属性、event、status の説明を、1 つの文字列にする。 */
  private static String renderedText(final SpanData span) {
    return Stream.of(
            Stream.of(
                span.getName(), span.getStatus().getDescription(), span.getAttributes().toString()),
            span.getEvents().stream().map(EventData::getAttributes).map(Object::toString))
        .flatMap(stream -> stream)
        .reduce("", String::concat);
  }
}
