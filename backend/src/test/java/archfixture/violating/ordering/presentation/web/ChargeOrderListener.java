package archfixture.violating.ordering.presentation.web;

import archfixture.violating.ordering.application.ChargeOrderCommandHandler;

/**
 * 違反：externalSystemCommandHandlersAreCalledOnlyByListeners（application の外に置いた、名前だけの Listener が呼ぶ）。
 */
class ChargeOrderListener {

  /** 外部システムに依存する CommandHandler。 */
  private final ChargeOrderCommandHandler chargeOrder;

  /** 依存を受け取る。 */
  /* package */ ChargeOrderListener(final ChargeOrderCommandHandler chargeOrder) {
    this.chargeOrder = chargeOrder;
  }

  /** 注文の代金を請求する。 */
  /* package */ void charge(final String orderId) {
    chargeOrder.handle(orderId);
  }
}
