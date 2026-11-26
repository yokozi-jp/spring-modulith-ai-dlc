package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.example.demo.testkit.CapturedSpans;
import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Boot の {@link RestClient.Builder} から作った client の送信の span と tag に、クエリと fragment、ヘッダー、body
 * の秘密が入らないことを検証する（ADR-140）。
 *
 * <p>Spring の既定は {@code http.url} と {@code uri} にクエリを入れるので、{@link
 * QueryFreeClientRequestObservationConvention} が落とすことを確かめる。 応答は 401 にして、例外を記録する span の event と
 * status の説明も調べる。
 */
@SuppressWarnings("PMD.TooManyStaticImports")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class ClientSpanContractTest {

  /** 送信先の URL からクエリを除いたもの。 */
  private static final String URL = "http://payment.example.test/v1/charges";

  /** 送信の span の、URL の属性（Spring の既定の名前）。 */
  private static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");

  /** 送信の span の、URI テンプレートの属性。 */
  private static final AttributeKey<String> URI = AttributeKey.stringKey("uri");

  /** 終えた span。 */
  @Autowired private CapturedSpans capturedSpans;

  /** Boot が observation を入れる client の builder。 */
  @Autowired private RestClient.Builder restClientBuilder;

  /** 指標。 */
  @Autowired private MeterRegistry meterRegistry;

  /** 秘密をクエリ、fragment、ヘッダー、body に入れて、401 を返す送信先を呼ぶ。 */
  private void callWithSecret(final String secret) {
    final MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
    server
        .expect(requestTo(Matchers.startsWith(URL)))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withUnauthorizedRequest());
    try {
      restClientBuilder
          .build()
          .post()
          .uri(URL + "?api_key=" + secret + "#" + secret)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
          .header(HttpHeaders.COOKIE, "SESSION=" + secret)
          .contentType(MediaType.APPLICATION_JSON)
          .body("{\"password\":\"" + secret + "\"}")
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientResponseException expected) {
      assertEquals(401, expected.getStatusCode().value(), "応答の status");
    }
    server.verify();
  }

  private List<SpanData> clientSpans() {
    return capturedSpans.all().stream()
        .filter(span -> span.getKind() == SpanKind.CLIENT)
        .filter(span -> URL.equals(span.getAttributes().get(HTTP_URL)))
        .toList();
  }

  @Test
  @DisplayName("送信の span の http.url と uri は、クエリと fragment を持たない")
  void clientSpanUrlHasNoQueryOrFragment() {
    final String secret = "S3CRET" + UUID.randomUUID();
    final int before = clientSpans().size();

    callWithSecret(secret);

    final List<SpanData> spans = clientSpans();
    assertEquals(before + 1, spans.size(), () -> "クエリなしの http.url を持つ送信の span: " + spans);
    assertEquals("/v1/charges", spans.getLast().getAttributes().get(URI), "uri の属性");
  }

  @Test
  @DisplayName("どの span も、名前、属性、event、status の説明に秘密を持たない")
  void noSpanCarriesSecret() {
    final String secret = "S3CRET" + UUID.randomUUID();

    callWithSecret(secret);

    final List<SpanData> failed =
        capturedSpans.all().stream()
            .filter(span -> span.getStatus().getStatusCode() == StatusCode.ERROR)
            .filter(span -> span.getKind() == SpanKind.CLIENT)
            .toList();
    assertFalse(failed.isEmpty(), "401 の例外を記録した送信の span がない");
    assertEquals(
        List.of(),
        capturedSpans.all().stream().filter(span -> span.toString().contains(secret)).toList(),
        "秘密を持つ span");
  }

  @Test
  @DisplayName("http.client.requests の uri の tag は、クエリを持たない")
  void clientMetricUriTagHasNoQuery() {
    final String secret = "S3CRET" + UUID.randomUUID();

    callWithSecret(secret);

    assertNotNull(
        meterRegistry.find("http.client.requests").tag("uri", "/v1/charges").timer(),
        "クエリなしの uri の tag を持つ timer がない");
    assertTrue(
        meterRegistry.find("http.client.requests").timers().stream()
            .noneMatch(timer -> timer.getId().getTag("uri").contains("?")),
        "クエリを持つ uri の tag がある");
  }
}
