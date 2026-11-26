package archfixture.violating.ordering.infrastructure.client;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.resilience.annotation.Retryable;

/** 違反：retryableIsNotUsedOnExternalSystemPath（Client のメソッドに Spring の {@code @Retryable} を付ける）。 */
public class RetryablePaymentClient implements PaymentGateway {

  @Retryable
  @Override
  public String charge(final OrderId orderId) {
    return "payment-" + orderId.value();
  }
}
