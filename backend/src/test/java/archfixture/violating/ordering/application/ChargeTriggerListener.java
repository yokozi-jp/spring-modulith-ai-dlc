package archfixture.violating.ordering.application;

/** 違反：commandHandlersAndListenersDoNotImplementInterfaces（{@link ChargeTrigger} を実装する）。 */
public class ChargeTriggerListener implements ChargeTrigger {

  @Override
  public void trigger(final String orderId) {
    // 受け取るだけ。
  }
}
