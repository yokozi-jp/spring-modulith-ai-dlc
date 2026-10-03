package archfixture.violating.order.infrastructure.client;

import archfixture.violating.order.domain.model.OrderId;
import archfixture.violating.order.domain.model.PaymentGateway;

/** 違反：externalSystemImplementationsAreClients（外部システムの実装を *Adapter と命名する）。 */
public class PaymentGatewayAdapter implements PaymentGateway {

  @Override
  public String charge(final OrderId orderId) {
    return "payment-" + orderId.value();
  }
}
