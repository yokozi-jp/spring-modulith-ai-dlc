package archfixture.eventcontract;

/**
 * プリミティブの {@code quantity} を足した型。古い出版では {@code 0} になる。
 *
 * @param orderId 注文の ID
 * @param quantity 足したプリミティブ
 */
public record PrimitiveAdded(String orderId, int quantity) {}
