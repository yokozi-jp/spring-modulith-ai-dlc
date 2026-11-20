package archfixture.violating.ordering.domain.model;

/**
 * 違反：externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients（domain.model
 * のクラスが外部システムのインタフェースに依存する）。
 */
public class PaymentCharger {

  /** 外部の決済システム。 */
  private final PaymentGateway paymentGateway;

  /** 依存を受け取る。 */
  public PaymentCharger(final PaymentGateway paymentGateway) {
    this.paymentGateway = paymentGateway;
  }

  /** 注文の代金を請求し、決済 ID を返す。 */
  public String charge(final OrderId orderId) {
    return paymentGateway.charge(orderId);
  }
}
