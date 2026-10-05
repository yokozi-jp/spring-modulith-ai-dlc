package archfixture.violating.order.presentation.web;

import archfixture.violating.order.application.ReleaseOrderCommand;

/** 違反：commandsBuiltByPresentationForWritesAreVersioned（版を持たない Command を作る Request）。 */
public record ReleaseOrderRequest(String orderId) {

  /** 版を持たない Command へ変換する。 */
  public ReleaseOrderCommand toCommand() {
    return new ReleaseOrderCommand(orderId);
  }
}
