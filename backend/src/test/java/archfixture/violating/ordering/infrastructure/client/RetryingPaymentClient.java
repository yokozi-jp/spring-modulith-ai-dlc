package archfixture.violating.ordering.infrastructure.client;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import java.util.concurrent.CompletableFuture;
import org.springframework.core.retry.RetryTemplate;

/**
 * 違反：resilience4jRetryAndTimeLimiterAreNotUsed（Client の中で再試行し、TimeLimiter で打ち切る）と
 * springRetryIsNotUsedOnExternalSystemPath（Client の中で {@code RetryTemplate} で再試行する）。
 */
public class RetryingPaymentClient implements PaymentGateway {

  @Retry(name = "payment-gateway")
  @Override
  public String charge(final OrderId orderId) {
    return "payment-" + orderId.value();
  }

  /** 決済の見込みを非同期に返す。 */
  @TimeLimiter(name = "payment-gateway")
  public CompletableFuture<String> quote(final OrderId orderId) {
    return CompletableFuture.completedFuture("quote-" + orderId.value());
  }

  /** 返金の ID を返す。 */
  public String refund(final OrderId orderId) {
    return new RetryTemplate().invoke(() -> "refund-" + orderId.value());
  }
}
