package archfixture.violating.ordering.application;

/**
 * 違反：commandHandlersAndListenersDoNotUseInheritance（役割名でない名前で {@link OrderShippedListener}
 * を実装し、実装したメソッドに pgm_cd が束縛されない）。
 */
public class OrderShippedReceiver implements OrderShippedListener {

  /** 受け取った注文 ID。 */
  private String lastOrderId = "";

  @Override
  public void receive(final String orderId) {
    lastOrderId = orderId;
  }

  /** 最後に受け取った注文 ID を返す。 */
  public String lastReceived() {
    return lastOrderId;
  }
}
