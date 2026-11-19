package archfixture.violating.ordering.infrastructure.client;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;

/**
 * 外部システムの Client。externalSystemCommandHandlersAreCalledOnlyByListeners は、この Client
 * が実装するインタフェースを外部システムとみなす。
 */
public class PaymentGatewayClient implements PaymentGateway {

  @Override
  public String charge(final OrderId orderId) {
    return "payment-" + orderId.value();
  }
}
