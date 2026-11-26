package archfixture.eventcontract;

/**
 * {@code @Nullable} でない {@code note} を足した型。
 *
 * @param orderId 注文の ID
 * @param note 足した必須の項目
 */
public record RequiredComponentAdded(String orderId, String note) {}
