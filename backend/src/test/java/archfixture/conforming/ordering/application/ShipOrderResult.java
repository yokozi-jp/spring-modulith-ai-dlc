package archfixture.conforming.ordering.application;

/** 注文を出荷したユースケースの出力。場合ごとの record で表す。 */
public sealed interface ShipOrderResult {

  /** 出荷した。 */
  record Shipped(String trackingNumber) implements ShipOrderResult {}

  /** すでに出荷済みだった。 */
  record AlreadyShipped() implements ShipOrderResult {}
}
