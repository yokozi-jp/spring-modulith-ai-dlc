package archfixture.violating.ordering.infrastructure.client;

/** 違反：numericIdsDoNotLeaveModules（外部のシステムへ数値の注文 ID を送る）。 */
public class ShippingLabelClient {

  /** 数値の注文 ID で送り状を印刷する。 */
  public String print(final int orderId) {
    return "label-" + orderId;
  }
}
