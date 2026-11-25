package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CapturedSpans;
import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 構造化ログ、トレース相関、OTLP 属性、テナントと利用者の文脈の運用契約を検証する。
 *
 * <p>契約ごとに分けると、同じ Bean の注入と appender の準備を重ねるので、1 つの class にまとめる。
 */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.TooManyMethods"})
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityContractTest {

  /** ログとトレースの、テナントの {@code public_id} の項目。 */
  private static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("tenant.id");

  /** ログとトレースの、アプリの利用者の ID の項目。 */
  private static final AttributeKey<String> USER_ID = AttributeKey.stringKey("user.id");

  /** 注文の API のパスの前半。 */
  private static final String ORDERS_PATH = "/api/orders/";

  /** HTTP などと同じ tracing handler が登録された observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

  /** OTLP へ送る直前の LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** このコンテキストの OpenTelemetry。 */
  @Autowired private OpenTelemetry openTelemetry;

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 終えた span。 */
  @Autowired private CapturedSpans capturedSpans;

  /** HTTP の observation が記録するメトリクス。 */
  @Autowired private MeterRegistry meterRegistry;

  @BeforeEach
  void installAppender() {
    // 別のコンテキストが appender の送り先を差し替えていても、このコンテキストの記録を読む。
    OpenTelemetryAppender.install(openTelemetry);
  }

  @Test
  @DisplayName("span 内の JSON ログには trace ID と span ID が含まれる")
  void structuredLogContainsTraceAndSpanIdentifiers(final CapturedOutput output) {
    final AtomicReference<String> traceId = new AtomicReference<>();
    final AtomicReference<String> spanId = new AtomicReference<>();
    final String message = "observability correlation contract";

    Observation.createNotStarted("observability.contract", observationRegistry)
        .observe(() -> {
          traceId.set(MDC.get("traceId"));
          spanId.set(MDC.get("spanId"));
          log.info(message);
        });

    assertNotNull(traceId.get(), "active observation の trace ID が MDC に存在すること");
    assertNotNull(spanId.get(), "active observation の span ID が MDC に存在すること");
    assertTrue(
        output.getAll().lines().anyMatch(line -> line.startsWith("{") && line.contains(message)),
        "対象ログが一行 JSON で出力されること");
    assertTrue(output.getAll().contains(traceId.get()), "JSON ログに trace ID が含まれること");
    assertTrue(output.getAll().contains(spanId.get()), "JSON ログに span ID が含まれること");
  }

  @Test
  @DisplayName("SLF4J の key-value は OTLP の LogRecord 属性になる")
  // appender が key-value を属性にすることだけを確かめる。order.id は Collector の allowlist にないため、
  // 実際の出口では除かれる（docs/observability/conventions.md）。
  void keyValuePairsBecomeLogRecordAttributes() {
    final String event = "observability key-value contract";

    log.atInfo().addKeyValue("order.id", "order-1").log(event);

    final Attributes attributes = singleRecordAttributes(event);
    assertEquals(
        "order-1",
        attributes.get(AttributeKey.stringKey("order.id")),
        () -> event + ": " + attributes);
  }

  @Test
  @DisplayName("logger へ渡した例外は OTLP の LogRecord に exception 属性として載る")
  void exceptionBecomesLogRecordAttributes() {
    final String event = "observability exception contract";

    log.atError().setCause(new IllegalStateException("contract failure")).log(event);

    final Attributes attributes = singleRecordAttributes(event);
    assertEquals(
        IllegalStateException.class.getName(),
        attributes.get(AttributeKey.stringKey("exception.type")),
        () -> event + ": " + attributes);
    assertEquals(
        "contract failure",
        attributes.get(AttributeKey.stringKey("exception.message")),
        () -> event + ": " + attributes);
    final String stackTrace = attributes.get(AttributeKey.stringKey("exception.stacktrace"));
    assertTrue(
        stackTrace != null && stackTrace.contains("ObservabilityContractTest"),
        () -> event + ": " + attributes);
  }

  @Test
  @DisplayName("MDC の tenant.id と user.id は、コンソールの JSON の入れ子の項目と OTLP の LogRecord 属性の両方に出る")
  void tenantAndUserInMdcAppearInConsoleAndLogRecord(final CapturedOutput output) {
    final String event = "observability mdc contract";

    try (MDC.MDCCloseable ignoredTenant = MDC.putCloseable(TENANT_ID.getKey(), AppUsers.TENANT_ID);
        MDC.MDCCloseable ignoredUser = MDC.putCloseable(USER_ID.getKey(), AppUsers.USER_ID)) {
      log.info(event);
    }

    final Attributes attributes = singleRecordAttributes(event);
    assertEquals(AppUsers.TENANT_ID, attributes.get(TENANT_ID), () -> event + ": " + attributes);
    assertEquals(AppUsers.USER_ID, attributes.get(USER_ID), () -> event + ": " + attributes);
    final JsonNode line =
        JsonMapper.builder()
            .build()
            .readTree(
                output
                    .getAll()
                    .lines()
                    .filter(candidate -> candidate.startsWith("{") && candidate.contains(event))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(event + " の JSON の行")));
    assertEquals(AppUsers.TENANT_ID, line.at("/tenant/id").asString(), line::toString);
    assertEquals(AppUsers.USER_ID, line.at("/user/id").asString(), line::toString);
  }

  @Test
  @DisplayName("テナントを選んだ利用者の要求の中のログは tenant.id と user.id を持ち、要求の後の MDC に残らない")
  void requestLogCarriesTenantAndUser() throws Exception {
    final String orderId = UUID.randomUUID().toString();

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/orders/{orderId}", orderId).with(AppUsers.oidcLogin()))
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    final Attributes attributes =
        capturedLogRecords.withBody("API business failure").stream()
            .map(LogRecordData::getAttributes)
            .filter(candidate ->
                String.valueOf(candidate.get(AttributeKey.stringKey("exception.message")))
                    .contains(orderId))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("orderId=" + orderId + " の API business failure"));
    assertEquals(AppUsers.TENANT_ID, attributes.get(TENANT_ID), attributes::toString);
    assertEquals(AppUsers.USER_ID, attributes.get(USER_ID), attributes::toString);
    assertNull(MDC.get(TENANT_ID.getKey()), "要求の後の MDC の tenant.id");
    assertNull(MDC.get(USER_ID.getKey()), "要求の後の MDC の user.id");
  }

  @Test
  @DisplayName(
      "テナントを選んだ利用者の要求の受信の span は tenant.id と user.id を属性に持ち、http.server.requests の tag には持たない")
  void serverSpanCarriesTenantAndUserButMetricsDoNot() throws Exception {
    final String orderId = UUID.randomUUID().toString();

    mockMvc
        .perform(
            MockMvcRequestBuilders.get("/api/orders/{orderId}", orderId).with(AppUsers.oidcLogin()))
        .andExpect(MockMvcResultMatchers.status().isNotFound());

    final List<SpanData> spans =
        capturedSpans.withAttribute("http.url", ORDERS_PATH + orderId).stream()
            .filter(span -> span.getKind() == SpanKind.SERVER)
            .toList();
    assertEquals(1, spans.size(), () -> "orderId=" + orderId + " の受信の span: " + spans);
    final Attributes attributes = spans.getFirst().getAttributes();
    assertEquals(AppUsers.TENANT_ID, attributes.get(TENANT_ID), attributes::toString);
    assertEquals(AppUsers.USER_ID, attributes.get(USER_ID), attributes::toString);
    final List<String> tagKeys =
        meterRegistry
            .find("http.server.requests")
            .tag("uri", "/api/orders/{orderId}")
            .meters()
            .stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .map(Tag::getKey)
            .distinct()
            .toList();
    assertFalse(tagKeys.isEmpty(), "GET /api/orders/{orderId} の http.server.requests");
    assertFalse(tagKeys.contains(TENANT_ID.getKey()), tagKeys::toString);
    assertFalse(tagKeys.contains(USER_ID.getKey()), tagKeys::toString);
  }

  @Test
  @DisplayName(
      "AuthorizationFilter が拒否した 403 のログは、user.id を持ち、拒否がテナントの束縛の外で記録されるので tenant.id を持たない")
  void authorizationFilterDenialLogCarriesUserWithoutTenant() throws Exception {
    // ログイン済みでも誰にも許可しない health の配下（ADR-061）。他のテストの受信の span と分けるため、パスを一意にする。
    final String path = "/actuator/health/" + UUID.randomUUID();

    mockMvc
        .perform(MockMvcRequestBuilders.get(path).with(AppUsers.oidcLogin()))
        .andExpect(MockMvcResultMatchers.status().isForbidden());

    assertDenialLogCarriesOnlyUserAndLeavesNoMdc(path);
  }

  @Test
  @DisplayName("@PreAuthorize が拒否した 403 のログは、テナントの束縛の中で記録されるので、user.id と tenant.id を持つ")
  void methodSecurityDenialLogCarriesUserAndTenant() throws Exception {
    final String path = "/api/current-tenant/members";

    mockMvc
        .perform(MockMvcRequestBuilders.get(path).with(AppUsers.oidcLogin()))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.type").value("about:blank"));

    final Attributes attributes = clientErrorAttributes(path);
    assertEquals(AppUsers.USER_ID, attributes.get(USER_ID), attributes::toString);
    assertEquals(AppUsers.TENANT_ID, attributes.get(TENANT_ID), attributes::toString);
    assertNull(MDC.get(TENANT_ID.getKey()), "要求の後の MDC の tenant.id");
  }

  @Test
  @DisplayName("テナントを選ぶ前の利用者の拒否のログは、user.id を持ち、tenant.id を持たない")
  void tenantNotSelectedLogCarriesUserWithoutTenant() throws Exception {
    final String path = ORDERS_PATH + UUID.randomUUID();
    final OidcUser user = AppUsers.oidcUser();

    mockMvc
        .perform(
            MockMvcRequestBuilders.get(path)
                .with(
                    SecurityMockMvcRequestPostProcessors.authentication(
                        new OAuth2AuthenticationToken(user, user.getAuthorities(), "web"))))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.type").value("/problems/tenant-not-selected"));

    assertDenialLogCarriesOnlyUserAndLeavesNoMdc(path);
  }

  @Test
  @DisplayName("CSRF の検証の失敗のログは、user.id を持ち、tenant.id を持たない")
  void csrfFailureLogCarriesUserWithoutTenant() throws Exception {
    final String path = ORDERS_PATH + UUID.randomUUID();

    mockMvc
        .perform(MockMvcRequestBuilders.post(path).with(AppUsers.oidcLogin()))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.type").value("/problems/csrf-token-invalid"));

    assertDenialLogCarriesOnlyUserAndLeavesNoMdc(path);
  }

  @Test
  @DisplayName("認証していない要求の 401 のログは、user.id も tenant.id も持たない")
  void unauthenticatedLogCarriesNeitherUserNorTenant() throws Exception {
    final String path = ORDERS_PATH + UUID.randomUUID();

    mockMvc
        .perform(MockMvcRequestBuilders.get(path))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());

    final Attributes attributes = clientErrorAttributes(path);
    assertNull(attributes.get(USER_ID), attributes::toString);
    assertNull(attributes.get(TENANT_ID), attributes::toString);
  }

  private void assertDenialLogCarriesOnlyUserAndLeavesNoMdc(final String path) {
    final Attributes attributes = clientErrorAttributes(path);
    assertEquals(AppUsers.USER_ID, attributes.get(USER_ID), attributes::toString);
    assertNull(attributes.get(TENANT_ID), attributes::toString);
    assertNull(MDC.get(USER_ID.getKey()), "要求の後の MDC の user.id");
  }

  /** {@code path} の要求の受信の span と同じ trace の、{@code ApiExceptionHandler} の 4xx のログの属性を返す。 */
  private Attributes clientErrorAttributes(final String path) {
    final List<String> traceIds =
        capturedSpans.withAttribute("http.url", path).stream()
            .filter(span -> span.getKind() == SpanKind.SERVER)
            .map(SpanData::getTraceId)
            .toList();
    assertEquals(1, traceIds.size(), () -> path + " の受信の span の trace: " + traceIds);
    final List<LogRecordData> records =
        capturedLogRecords.withBody("API client error").stream()
            .filter(record -> traceIds.getFirst().equals(record.getSpanContext().getTraceId()))
            .toList();
    assertEquals(1, records.size(), () -> path + " の API client error: " + records);
    return records.getFirst().getAttributes();
  }

  private Attributes singleRecordAttributes(final String event) {
    final List<LogRecordData> records = capturedLogRecords.withBody(event);
    assertEquals(1, records.size(), () -> "body=" + event + " の LogRecord 件数");
    return records.getFirst().getAttributes();
  }
}
