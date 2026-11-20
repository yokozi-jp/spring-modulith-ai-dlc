package archfixture.violating.ordering.application;

/**
 * 違反：commandHandlersAndListenersDoNotImplementInterfaces（Listener に実装させ、呼び出し側にこの interface だけを見せて
 * Listener への依存の検査を迂回する）。
 */
@FunctionalInterface
public interface ChargeTrigger {

  /** 請求を始める。 */
  void trigger(String orderId);
}
