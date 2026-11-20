package archfixture.violating.ordering.domain.service;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.PaymentGateway;
import org.springframework.stereotype.Service;

/**
 * 違反：externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients（Domain Service
 * が外部システムのインタフェースに依存する）。
 */
@Service
public class PaymentChargePolicy {

  /** 外部の決済システム。 */
  private final PaymentGateway paymentGateway;

  /** 依存を受け取る。 */
  public PaymentChargePolicy(final PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  /** 注文の代金を請求し、決済 ID を返す。 */
  public String charge(final OrderId orderId) {
    return paymentGateway.charge(orderId);
  }
}
