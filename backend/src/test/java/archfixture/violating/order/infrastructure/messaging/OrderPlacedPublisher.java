package archfixture.violating.order.infrastructure.messaging;

/** 違反：dependenciesPointInward（廃止した infrastructure.messaging に置く）。 */
public final class OrderPlacedPublisher {

  /** 送信先のトピック名を返す。 */
  public String topic() {
    return "order-placed";
  }
}
