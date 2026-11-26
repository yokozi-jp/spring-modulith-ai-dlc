package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * HTTP の受信の指標が OpenTelemetry の semantic conventions に従う契約を検証する（ADR-133）。
 *
 * <p>OTLP のレジストリはテストのコンテキストに無いので、送信の単位は設定の束縛で確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class HttpServerMetricsContractTest {

  /** HTTP の受信の時間の Timer。 */
  private static final String HTTP_SERVER_DURATION = "http.server.request.duration";

  /** 注文を 1 件読む API の route。 */
  private static final String ORDER_ROUTE = "/api/orders/{orderId}";

  /** semantic conventions が推奨する、HTTP の受信の時間の bucket の境界（秒）。 */
  private static final List<Double> SEMCONV_BUCKET_BOUNDS_SECONDS =
      List.of(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0);

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** HTTP の observation が記録するメトリクス。 */
  @Autowired private MeterRegistry meterRegistry;

  /** 設定を束縛するための環境。 */
  @Autowired private Environment environment;

  /** 注文が無い GET の 404 の受信の時間。 */
  private Timer orderNotFoundTimer;

  @BeforeEach
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  void recordOrderNotFound() throws Exception {
    mockMvc
        .perform(
            MockMvcRequestBuilders.get(ORDER_ROUTE, UUID.randomUUID()).with(AppUsers.oidcLogin()))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
    orderNotFoundTimer =
        meterRegistry
            .find(HTTP_SERVER_DURATION)
            .tag("http.route", ORDER_ROUTE)
            .tag("http.response.status_code", "404")
            .timer();
    assertNotNull(orderNotFoundTimer, "GET " + ORDER_ROUTE + " の 404 の " + HTTP_SERVER_DURATION);
  }

  @Test
  @DisplayName("テナントを選んだ利用者の要求でも、受信の時間の tag に tenant.id と user.id を持たない")
  void tenantAndUserAreNotMetricTags() {
    final List<String> tagKeys =
        orderNotFoundTimer.getId().getTags().stream().map(Tag::getKey).toList();
    assertFalse(tagKeys.contains("tenant.id"), tagKeys::toString);
    assertFalse(tagKeys.contains("user.id"), tagKeys::toString);
  }

  @Test
  @DisplayName("受信の時間は、semantic conventions の推奨の境界の bucket を持つ")
  void serverDurationHasSemanticConventionBuckets() {
    // bucket が無いと OTLP は +Inf だけを送り、Grafana の histogram_quantile が値を返さない。
    final List<Double> bounds =
        Arrays.stream(orderNotFoundTimer.takeSnapshot().histogramCounts())
            .map(bucket -> bucket.bucket(TimeUnit.SECONDS))
            .toList();
    assertEquals(SEMCONV_BUCKET_BOUNDS_SECONDS, bounds, "bucket の境界（秒）");
  }

  @Test
  @DisplayName("OTLP の指標は、時間を秒で送る設定になっている")
  void otlpExportsTimeInSeconds() {
    final OtlpMetricsProperties properties =
        Binder.get(environment)
            .bind("management.otlp.metrics.export", OtlpMetricsProperties.class)
            .get();
    assertEquals(
        TimeUnit.SECONDS,
        properties.getBaseTimeUnit(),
        "management.otlp.metrics.export.base-time-unit");
  }
}
