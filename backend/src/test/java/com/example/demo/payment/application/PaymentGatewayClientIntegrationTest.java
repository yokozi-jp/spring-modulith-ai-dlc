package com.example.demo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.payment.PaymentQueries;
import com.example.demo.payment.PaymentSearchCriteria;
import com.example.demo.payment.PaymentSummary;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;

/**
 * 決済代行の Client を WireMock に向け、確定した注文の請求の成功と失敗、retry と circuit breaker を検証する。
 *
 * <p>成功の応答は、compose の WireMock と同じ {@code docker/wiremock/mappings} のスタブで返し、Client
 * とスタブの契約が合うことを確かめる。 失敗は、注文 ID の冪等性キーに合う優先度の高いスタブをテストの中で足す。決済代行を差し替えず、Resilience4j の retry と
 * circuit breaker を通す。URL の設定の違いで {@link OrderConfirmedListenerTest} とは別の Spring のコンテキストになる。
 */
// 成功、5xx、タイムアウト、遅延、circuit breaker を一つの WireMock の文脈で確かめるため、メソッドが多い。
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

    fixture.confirmAndAwait(scenario, orderId, "完了した出版", state -> state.archived() == 1);

    final List<PaymentSummary> payments = paymentQueries.search(new PaymentSearchCriteria(orderId));
    assertThat(payments).as("orderId=%s の決済記録", orderId).hasSize(1);
    assertThat(payments.getFirst().gatewayPaymentCode())
        .as("orderId=%s の決済代行の識別子", orderId)
        .isEqualTo("ch_" + orderId);
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
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo(CHARGES))
            .atPriority(1)
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId))
            .willReturn(WireMock.serviceUnavailable()));

    fixture.confirmAndAwait(
        scenario, orderId, "FAILED の出版", state -> "FAILED".equals(state.status()));

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
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo(CHARGES))
            .atPriority(1)
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId))
            .willReturn(succeeded(orderId).withFixedDelay(3000)));

    fixture.confirmAndAwait(
        scenario, orderId, "FAILED の出版", state -> "FAILED".equals(state.status()));

    assertFailedOnce(orderId);
  }

  @Test
  @DisplayName("応答が遅れてもタイムアウトの 2 秒より短ければ、決済記録を作る")
  void slowReplyWithinTimeoutCharges(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    WIREMOCK.stubFor(
        WireMock.post(WireMock.urlEqualTo(CHARGES))
            .atPriority(1)
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId))
            .willReturn(succeeded(orderId).withFixedDelay(1000)));

    fixture.confirmAndAwait(scenario, orderId, "完了した出版", state -> state.archived() == 1);

    assertThat(paymentQueries.search(new PaymentSearchCriteria(orderId)))
        .as("orderId=%s の決済記録", orderId)
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

  /** 決済記録を作らず、出版が attempts 1 で FAILED に残り、決済代行が 1 回だけ呼ばれたことを確かめる。 */
  private void assertFailedOnce(final String orderId) {
    assertThat(fixture.registry(orderId).attempts())
        .as("orderId=%s の FAILED の attempts", orderId)
        .isEqualTo(1);
    assertThat(paymentQueries.search(new PaymentSearchCriteria(orderId)))
        .as("orderId=%s の決済記録", orderId)
        .isEmpty();
    WIREMOCK.verify(
        1,
        WireMock.postRequestedFor(WireMock.urlEqualTo(CHARGES))
            .withHeader(IDEMPOTENCY_KEY, WireMock.equalTo(orderId)));
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
    return WireMock.aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"chargeId\":\"ch_" + orderId + "\",\"status\":\"SUCCEEDED\"}");
  }
}
