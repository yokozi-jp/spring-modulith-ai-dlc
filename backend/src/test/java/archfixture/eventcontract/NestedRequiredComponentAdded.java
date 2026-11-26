package archfixture.eventcontract;

/**
 * 入れ子の record に {@code @Nullable} でない {@code note} を足した型。
 *
 * @param orderId 注文の ID
 * @param item 注文の明細
 */
public record NestedRequiredComponentAdded(String orderId, Item item) {

  /**
   * 注文の明細。
   *
   * @param sku 商品の ID
   * @param note 足した必須の項目
   */
  public record Item(String sku, String note) {}
}
