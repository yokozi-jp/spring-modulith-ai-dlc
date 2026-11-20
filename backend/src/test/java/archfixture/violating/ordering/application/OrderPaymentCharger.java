package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.stereotype.Component;

/**
 * 違反：externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients（CommandHandler
 * ではない補助のクラスが外部システムを呼ぶ）。
 */
@Component
public class OrderPaymentCharger {

  /** 外部の決済システム。 */
  private final PaymentGateway paymentGateway;

  /** 依存を受け取る。 */
  public OrderPaymentCharger(final PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  /** 注文の代金を請求し、決済 ID を返す。 */
  public String charge(final String orderId) {
    return paymentGateway.charge(new OrderId(orderId));
  }
}
