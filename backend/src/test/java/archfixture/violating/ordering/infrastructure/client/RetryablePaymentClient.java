package archfixture.violating.ordering.infrastructure.client;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.resilience.annotation.Retryable;

/** 違反：springRetryIsNotUsedOnExternalSystemPath（Client のクラスに Spring の {@code @Retryable} を付ける）。 */
@Retryable
public class RetryablePaymentClient implements PaymentGateway {

  @Override
  public String charge(final OrderId orderId) {
    return "payment-" + orderId.value();
  }
}
