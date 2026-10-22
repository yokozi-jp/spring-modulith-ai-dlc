package archfixture.violating.ordering.application;

/**
 * 違反：commandHandlersAndListenersDoNotUseInheritance（役割名でない {@link OrderShippedReceiver} に実装させる）。
 */
@FunctionalInterface
public interface OrderShippedListener {

  /** 出荷を受け取る。 */
  void receive(String orderId);
}
