package archfixture.violating.ordering.application;

/**
 * 違反：commandHandlersAndListenersDoNotUseInheritance（{@link RestoreOrderCommandHandler} の基底クラスになる）。
 */
public class OrderHandlerSupport {

  /** 注文 ID が空でないかを返す。 */
  public boolean handle(final String orderId) {
    return !orderId.isBlank();
  }
}
