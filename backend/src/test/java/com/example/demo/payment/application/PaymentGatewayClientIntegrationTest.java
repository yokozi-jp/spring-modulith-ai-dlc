package com.example.demo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.payment.PaymentQueries;
import com.example.demo.payment.PaymentSearchCriteria;
import com.example.demo.payment.PaymentSummary;
import com.example.demo.payment.domain.model.ChargeOutcome;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/**
 * 決済代行の Client を WireMock に向け、確定した注文の請求の成功と失敗、retry と circuit breaker を検証する。
 *
 * <p>成功の応答は、compose の WireMock と同じ {@code docker/wiremock/mappings} のスタブで返し、Client
 * とスタブの契約が合うことを確かめる。 失敗は、注文 ID の冪等性キーに合う優先度の高いスタブをテストの中で足す。決済代行を差し替えず、Resilience4j の retry と
 * circuit breaker を通す。ADR-072 の四つの分類ごとに、出版の状態（拒否と契約の不備は COMPLETED、一時障害と資格情報の不備は FAILED）と決済記録を確かめる。
 * 429 以外の 4xx は circuit breaker が失敗として数えず、429 は数える。出版の状態を読む公開の入口も再投入の公開の入口も無く、定期の再投入（ADR-075）は
 * テストで無効にしているため、5xx とタイムアウトの FAILED から既存の再投入の入口（{@link ResubmitFailedPaymentsRunner}）で回復し、決済記録が 1
 * 件になることと、同じイベントの再配送で請求しないことを、本番の Client を通してここで確かめる（E2E は画面に見える状態だけを確かめる）。URL の設定の違いで {@link
 * OrderConfirmedListenerTest} とは別の Spring のコンテキストになる。
 */
// 成功、拒否、契約に合わない応答、5xx、4xx、429、タイムアウト、遅延、circuit breaker を一つの WireMock の文脈で確かめるため、メソッドが多い。
@SuppressWarnings("PMD.TooManyMethods")
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@Import({SharedTestConfiguration.class, OrderConfirmedFixture.class})
@ExtendWith(CleanGeneratedTablesExtension.class)
class PaymentGatewayClientIntegrationTest {

  /** 決済代行の Resilience4j の instance の名前。 */
  private static final String GATEWAY = "payment-gateway";

  /** 請求の API のパス。 */
  private static final String CHARGES = "/v1/charges";

  /** 決済代行が冪等性キーを受け取るヘッダー。 */
  private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** 共有のスタブが注文 ID の前に付ける、決済代行の識別子の接頭辞。 */
  private static final String CHARGE_ID_PREFIX = "ch_";

  /** イベント出版の状態と、請求の結果の FAILED。 */
  private static final String FAILED = "FAILED";

  /** 完了した出版を待つときの説明。 */
  private static final String COMPLETED_PUBLICATION = "完了した出版";

  /** FAILED の出版を待つときの説明。 */
  private static final String FAILED_PUBLICATION = "FAILED の出版";

  /** 決済記録の検査の説明。 */
  private static final String PAYMENTS_OF = "orderId=%s の決済記録";

  /** 再び受けた OrderConfirmed の確定の時刻。 */
  private static final Instant REDELIVERED_AT = Instant.parse("2026-10-06T01:02:03.123456Z");

  /** circuit breaker が open になるまでの失敗の数（application.yaml の minimum-number-of-calls）。 */
  private static final int CALLS_TO_OPEN = 10;

  /**
   * 決済代行の代わりの WireMock。Gradle のテストの作業ディレクトリは backend なので、リポジトリの docker/wiremock を読む。
   *
   * <p>Spring のコンテキストが URL を読むより前に起動するため、JUnit の拡張でなく static の初期化で起動する。
   */
  private static final WireMockServer WIREMOCK = startWireMock();

  /** 確定する注文を作り、出版の状態を読む補助。 */
  @Autowired private OrderConfirmedFixture fixture;

  /** 決済の参照。 */
  @Autowired private PaymentQueries paymentQueries;

  /** Spring Modulith の失敗した出版の再投入。再投入の入口の Runner に渡す。 */
  @Autowired private FailedEventPublications failedEventPublications;

  /** Resilience4j の aspect を通る決済代行の Client。 */
  @Autowired private PaymentGateway paymentGateway;

  /** 決済代行の circuit breaker を読む。 */
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  /** 決済代行の circuit breaker。 */
  private CircuitBreaker circuitBreaker;

  /** WireMock を止める。 */
  @AfterAll
  static void stopWireMock() {
    WIREMOCK.stop();
  }

  /** 決済代行の URL を WireMock に向ける。 */
  @DynamicPropertySource
  /* package */ static void gatewayUrl(final DynamicPropertyRegistry registry) {
    registry.add("payment-gateway.base-url", WIREMOCK::baseUrl);
  }

  /** テストの順序に依存しないよう、スタブと受けた要求を共有のスタブだけに戻し、circuit breaker を閉じて件数を消す。 */
  @BeforeEach
  void reset() {
    WIREMOCK.resetToDefaultMappings();
    WIREMOCK.resetRequests();
    circuitBreaker = circuitBreakerRegistry.circuitBreaker(GATEWAY);
    circuitBreaker.reset();
  }

  @Test
  @DisplayName("共有のスタブで成功し、注文 ID を冪等性キーにした請求の本文を 1 回送り、chargeId で決済記録を作る")
  void chargesThroughSharedStub(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();

    fixture.confirmAndAwait(
        scenario, orderId, COMPLETED_PUBLICATION, state -> state.archived() == 1);

    assertRecordedOnce(orderId, "PAID", CHARGE_ID_PREFIX + orderId);
    WIREMOCK.verify(
        1,
        WireMock.postRequestedFor(WireMock.urlEqualTo(CHARGES))
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId))
            .withRequestBody(
                WireMock.equalToJson(
                    "{\"orderId\":\"" + orderId + "\",\"amount\":240.00,\"currency\":\"JPY\"}",
                    true,
                    false)));
  }

  @Test
  @DisplayName("決済代行が 5xx を返せば 1 回だけ呼ばれ、決済記録を作らず、出版は FAILED、attempts 1 で残り、circuit breaker は閉じたまま")
  void serverErrorLeavesPublicationFailed(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    stubCharge(orderId, WireMock.serviceUnavailable());

    fixture.confirmAndAwait(
        scenario, orderId, FAILED_PUBLICATION, state -> FAILED.equals(state.status()));

    assertFailedOnce(orderId);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
        .as("orderId=%s の請求で circuit breaker が数えた失敗", orderId)
        .isEqualTo(1);
    assertThat(circuitBreaker.getState())
        .as("1 回の失敗では閉じたまま")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @DisplayName("応答が呼び出しのタイムアウト 2 秒を超えれば、決済記録を作らず、出版は FAILED、attempts 1 で残る")
  void timeoutLeavesPublicationFailed(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    stubCharge(orderId, succeeded(orderId).withFixedDelay(3000));

    fixture.confirmAndAwait(
        scenario, orderId, FAILED_PUBLICATION, state -> FAILED.equals(state.status()));

    assertFailedOnce(orderId);
  }

  @Test
  @DisplayName("応答が遅れてもタイムアウトの 2 秒より短ければ、決済記録を作る")
  void slowReplyWithinTimeoutCharges(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    stubCharge(orderId, succeeded(orderId).withFixedDelay(1000));

    fixture.confirmAndAwait(
        scenario, orderId, COMPLETED_PUBLICATION, state -> state.archived() == 1);

    assertThat(paymentQueries.search(new PaymentSearchCriteria(orderId)))
        .as(PAYMENTS_OF, orderId)
        .hasSize(1);
  }

  @Test
  @DisplayName(
      "10 回続けて失敗すると circuit breaker が open になり、次の請求は決済代行を呼ばずに CallNotPermittedException で断る")
  void consecutiveFailuresOpenCircuitBreaker() {
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo(CHARGES))
            .atPriority(1)
            .willReturn(WireMock.serviceUnavailable()));
    final Money amount = new Money(new BigDecimal("1.00"));
    final OrderId orderId = new OrderId(UUID.randomUUID());

    for (int call = 0; call < CALLS_TO_OPEN; call++) {
      assertThatThrownBy(() -> paymentGateway.charge(orderId, amount))
          .isInstanceOf(HttpServerErrorException.class);
    }

    assertThat(circuitBreaker.getState())
        .as("%d 回の失敗の後の circuit breaker", CALLS_TO_OPEN)
        .isEqualTo(CircuitBreaker.State.OPEN);
    assertThatThrownBy(() -> paymentGateway.charge(orderId, amount))
        .isInstanceOf(CallNotPermittedException.class);
    WIREMOCK.verify(CALLS_TO_OPEN, WireMock.postRequestedFor(WireMock.urlEqualTo(CHARGES)));
  }

  @Test
  @DisplayName("決済代行が DECLINED を返せば、拒否を決済記録に残し、出版を完了にする")
  void declinedIsRecordedAndCompletes(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    stubCharge(orderId, reply(orderId, "DECLINED"));

    fixture.confirmAndAwait(
        scenario, orderId, COMPLETED_PUBLICATION, state -> state.archived() == 1);

    assertRecordedOnce(orderId, "DECLINED", CHARGE_ID_PREFIX + orderId);
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '\'',
      value = {
        "400 | ''",
        "200 | {\"chargeId\":\"ch_1\",\"status\":\"SUCCEEDED\"}",
        "202 | {\"chargeId\":\"ch_1\",\"status\":\"SUCCEEDED\"}",
        "201 | ''",
        "201 | {\"chargeId\":\"ch_1\",\"status\":\"PENDING\"}",
        "201 | {\"chargeId\":\" \",\"status\":\"SUCCEEDED\"}",
        "201 | {\"status\":\"DECLINED\"}",
        "201 | {\"chargeId\":\"\",\"status\":\"DECLINED\"}",
        "200 | {\"chargeId\":\"ch_1\",\"status\":\"DECLINED\"}",
      })
  @DisplayName("決済代行が契約の不備の 4xx か契約に合わない応答を返せば、失敗を決済記録に残し、出版を完了にし、circuit breaker は失敗に数えない")
  void contractViolatingReplyIsRecordedAndCompletes(
      final int status, final String body, final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    stubCharge(
        orderId,
        WireMock.aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(body));

    fixture.confirmAndAwait(
        scenario, orderId, COMPLETED_PUBLICATION, state -> state.archived() == 1);

    assertRecordedOnce(orderId, FAILED, null);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
        .as("orderId=%s の %d %s で circuit breaker が数えた失敗", orderId, status, body)
        .isZero();
  }

  @Test
  @DisplayName("決済代行が 401 を返せば、決済記録を作らず、出版は FAILED、attempts 1 で残り、circuit breaker は失敗に数えない")
  void unauthorizedLeavesPublicationFailed(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    stubCharge(orderId, WireMock.unauthorized());

    fixture.confirmAndAwait(
        scenario, orderId, FAILED_PUBLICATION, state -> FAILED.equals(state.status()));

    assertFailedOnce(orderId);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
        .as("orderId=%s の 401 で circuit breaker が数えた失敗", orderId)
        .isZero();
  }

  @Test
  @DisplayName("決済代行が 429 を返せば、決済記録を作らず、出版は FAILED、attempts 1 で残り、circuit breaker は失敗に数える")
  void tooManyRequestsLeavesPublicationFailed(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    stubCharge(orderId, WireMock.status(429));

    fixture.confirmAndAwait(
        scenario, orderId, FAILED_PUBLICATION, state -> FAILED.equals(state.status()));

    assertFailedOnce(orderId);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
        .as("orderId=%s の 429 で circuit breaker が数えた失敗", orderId)
        .isEqualTo(1);
  }

  @Test
  @DisplayName("決済代行が 4xx を返し続けても、circuit breaker は失敗として数えず閉じたままで、再試行もしない")
  void clientErrorsAreNotCountedOrRetried() {
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo(CHARGES))
            .atPriority(1)
            .willReturn(WireMock.badRequest()));
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo(CHARGES))
            .atPriority(1)
            .withRequestBody(WireMock.containing("\"amount\":2.00"))
            .willReturn(WireMock.forbidden()));
    final OrderId orderId = new OrderId(UUID.randomUUID());
    final Money badRequestAmount = new Money(new BigDecimal("1.00"));
    final Money forbiddenAmount = new Money(new BigDecimal("2.00"));
    final ChargeOutcome failed = ChargeOutcome.failed();

    for (int call = 0; call < CALLS_TO_OPEN; call++) {
      assertThat(paymentGateway.charge(orderId, badRequestAmount))
          .as("400 の請求の結果")
          .isEqualTo(failed);
      assertThatThrownBy(() -> paymentGateway.charge(orderId, forbiddenAmount))
          .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    assertThat(circuitBreaker.getState())
        .as("%d 回ずつの 400 と 403 の後の circuit breaker", CALLS_TO_OPEN)
        .isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
        .as("%d 回ずつの 400 と 403 で circuit breaker が数えた失敗", CALLS_TO_OPEN)
        .isZero();
    WIREMOCK.verify(2 * CALLS_TO_OPEN, WireMock.postRequestedFor(WireMock.urlEqualTo(CHARGES)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"5xx", "timeout"})
  @DisplayName(
      "一時障害で FAILED、attempts 1 に残った出版は、再投入の入口で attempts 2 の COMPLETED になり、決済記録は 1 件で、同じイベントを再び受けても請求しない")
  void failedPublicationIsRecoveredByResubmission(final String failure, final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    final StubMapping failing =
        stubCharge(
            orderId,
            "5xx".equals(failure)
                ? WireMock.serviceUnavailable()
                : succeeded(orderId).withFixedDelay(3000));

    // 1. 一時障害：出版は FAILED、attempts 1、決済記録はない。
    fixture.confirmAndAwait(
        scenario, orderId, FAILED_PUBLICATION, state -> FAILED.equals(state.status()));
    assertFailedOnce(orderId);

    // 2. 決済代行を共有の成功のスタブに戻し、既存の再投入の入口（resubmit-once のプロファイルの Runner）を呼ぶ。
    WIREMOCK.removeStub(failing);
    OrderConfirmedFixture.await(
        scenario.stimulate(
            () ->
                new ResubmitFailedPaymentsRunner(failedEventPublications)
                    .run(new DefaultApplicationArguments())),
        orderId,
        "再投入の完了した出版",
        () -> fixture.registry(orderId),
        state -> state.archived() == 1);
    final OrderConfirmedFixture.RegistryState completed = fixture.registry(orderId);
    assertThat(completed.status()).as("orderId=%s の再投入の後の状態", orderId).isEqualTo("COMPLETED");
    assertThat(completed.attempts()).as("orderId=%s の再投入の後の attempts", orderId).isEqualTo(2);
    assertRecorded(orderId, "PAID", CHARGE_ID_PREFIX + orderId, 2);

    // 3. 同じ注文の OrderConfirmed をもう一度受けても、請求せず、決済記録は 1 件のまま。
    OrderConfirmedFixture.await(
        scenario.publish(new OrderConfirmed(orderId, REDELIVERED_AT)),
        orderId,
        "2 件目の完了した出版",
        () -> fixture.registry(orderId),
        state -> state.archived() == 2);
    assertRecorded(orderId, "PAID", CHARGE_ID_PREFIX + orderId, 2);
  }

  /** 決済記録が 1 件だけで、結果と識別子が期待どおりで、決済代行が 1 回だけ呼ばれたことを確かめる。 */
  private void assertRecordedOnce(
      final String orderId, final String status, final @Nullable String gatewayPaymentCode) {
    assertRecorded(orderId, status, gatewayPaymentCode, 1);
  }

  /** 決済記録が 1 件だけで、結果と識別子が期待どおりで、決済代行が同じ冪等性キーで指定の回数だけ呼ばれたことを確かめる。 */
  private void assertRecorded(
      final String orderId,
      final String status,
      final @Nullable String gatewayPaymentCode,
      final int calls) {
    final List<PaymentSummary> payments = paymentQueries.search(new PaymentSearchCriteria(orderId));
    assertThat(payments).as(PAYMENTS_OF, orderId).hasSize(1);
    assertThat(payments.getFirst().status()).as("orderId=%s の請求の結果", orderId).isEqualTo(status);
    assertThat(payments.getFirst().gatewayPaymentCode())
        .as("orderId=%s の決済代行の識別子", orderId)
        .isEqualTo(gatewayPaymentCode);
    WIREMOCK.verify(
        calls,
        WireMock.postRequestedFor(WireMock.urlEqualTo(CHARGES))
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId)));
  }

  /** 決済記録を作らず、出版が attempts 1 で FAILED に残り、決済代行が 1 回だけ呼ばれたことを確かめる。 */
  private void assertFailedOnce(final String orderId) {
    assertThat(fixture.registry(orderId).attempts())
        .as("orderId=%s の FAILED の attempts", orderId)
        .isEqualTo(1);
    assertThat(paymentQueries.search(new PaymentSearchCriteria(orderId)))
        .as(PAYMENTS_OF, orderId)
        .isEmpty();
    WIREMOCK.verify(
        1,
        WireMock.postRequestedFor(WireMock.urlEqualTo(CHARGES))
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId)));
  }

  /** 注文 ID の冪等性キーの請求にだけ、共有のスタブより優先して返す応答を足す。 */
  private static StubMapping stubCharge(
      final String orderId, final ResponseDefinitionBuilder response) {
    return WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo(CHARGES))
            .atPriority(1)
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId))
            .willReturn(response));
  }

  /** 空いているポートで、共有のスタブを読んだ WireMock を起動する。 */
  private static WireMockServer startWireMock() {
    final WireMockServer server =
        new WireMockServer(
            WireMockConfiguration.wireMockConfig()
                .dynamicPort()
                // compose の WireMock と同じく、平文の HTTP/2 への upgrade を止める。
                .http2PlainDisabled(true)
                .usingFilesUnderDirectory("../docker/wiremock"));
    server.start();
    return server;
  }

  /** 注文の成功の応答。 */
  private static ResponseDefinitionBuilder succeeded(final String orderId) {
    return reply(orderId, "SUCCEEDED");
  }

  /** 注文の 201 の応答。状態は SUCCEEDED か DECLINED。 */
  private static ResponseDefinitionBuilder reply(final String orderId, final String status) {
    return WireMock.aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody(
            "{\"chargeId\":\"" + CHARGE_ID_PREFIX + orderId + "\",\"status\":\"" + status + "\"}");
  }
}
