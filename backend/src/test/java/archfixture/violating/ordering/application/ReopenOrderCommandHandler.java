package archfixture.violating.ordering.application;

/** 違反：commandHandlersAndListenersDoNotUseInheritance（役割名でない {@link ReopenOrderHandler} に継承させる）。 */
public class ReopenOrderCommandHandler {

  /** 注文を再開したかを返す。 */
  public boolean handle(final String orderId) {
    return !orderId.isBlank();
  }
}
