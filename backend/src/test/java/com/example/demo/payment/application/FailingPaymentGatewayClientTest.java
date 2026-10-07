package com.example.demo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.payment.PaymentQueries;
import com.example.demo.payment.PaymentSearchCriteria;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;
import org.springframework.test.context.TestPropertySource;

/**
 * アプリの決済代行の偽物（{@code PaymentGatewayClient}）を FAIL にして、確定した注文の請求の失敗で出版が FAILED で残ることを検証する。
 *
 * <p>決済代行を差し替えず、Resilience4j の retry と circuit breaker を通す。{@code payment-gateway.mode} の違いで {@link
 * OrderConfirmedListenerTest} とは別の Spring のコンテキストになり、そちらの文脈の決済代行の状態と circuit breaker の件数を変えない。
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@TestPropertySource(properties = "payment-gateway.mode=FAIL")
@Import({SharedTestConfiguration.class, OrderConfirmedFixture.class})
@ExtendWith(CleanGeneratedTablesExtension.class)
class FailingPaymentGatewayClientTest {

  /** 決済代行の Resilience4j の instance の名前。 */
  private static final String GATEWAY = "payment-gateway";

  /** 確定する注文を作り、出版の状態を読む補助。 */
  @Autowired private OrderConfirmedFixture fixture;

  /** 決済の参照。 */
  @Autowired private PaymentQueries paymentQueries;

  /** 決済代行の circuit breaker を読む。 */
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  /** 決済代行の retry を読む。 */
  @Autowired private RetryRegistry retryRegistry;

  @Test
  @DisplayName("FAIL の決済代行では、retry の設定どおり 1 回だけ呼ばれ、決済記録を作らず、出版は FAILED、attempts 1 で残る")
  void failingGatewayLeavesPublicationFailed(final Scenario scenario) {
    final CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(GATEWAY);
    final Retry retry = retryRegistry.retry(GATEWAY);
    final long failedCallsBefore = circuitBreaker.getMetrics().getNumberOfFailedCalls();
    final long retryFailuresBefore = failures(retry);
    final String orderId = fixture.draftedOrderId();

    fixture.confirmAndAwait(
        scenario, orderId, "FAILED の出版", state -> "FAILED".equals(state.status()));

    assertThat(fixture.registry(orderId).attempts())
        .as("orderId=%s の FAILED の attempts", orderId)
        .isEqualTo(1);
    assertThat(paymentQueries.search(new PaymentSearchCriteria(orderId)))
        .as("orderId=%s の決済記録", orderId)
        .isEmpty();
    assertThat(retry.getRetryConfig().getMaxAttempts()).as("retry の試行の上限").isEqualTo(1);
    assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls())
        .as("circuit breaker が数えた失敗の呼び出し。1 件なら決済代行は 1 回だけ呼ばれた")
        .isEqualTo(failedCallsBefore + 1);
    assertThat(failures(retry))
        .as("retry が数えた失敗の呼び出し。retry を通った 1 回の請求が失敗で終わった")
        .isEqualTo(retryFailuresBefore + 1);
    assertThat(circuitBreaker.getState())
        .as("1 回の失敗では閉じたまま")
        .isEqualTo(CircuitBreaker.State.CLOSED);
  }

  /** retry が数えた失敗の呼び出しの数。試行の上限が 1 回でも、Resilience4j は再試行ありの失敗に数えることがあるため、両方を足す。 */
  private static long failures(final Retry retry) {
    return retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
        + retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt();
  }
}
