package archfixture.eventcontract;

import java.util.List;

/**
 * リストの要素の record に {@code @Nullable} でない {@code note} を足した型。
 *
 * @param orderId 注文の ID
 * @param items 注文の明細
 */
public record ListElementRequiredComponentAdded(String orderId, List<Item> items) {

  /** 明細を変更できない複製にする。 */
  public ListElementRequiredComponentAdded {
    items = List.copyOf(items);
  }

  /**
   * 注文の明細。
   *
   * @param sku 商品の ID
   * @param note 足した必須の項目
   */
  public record Item(String sku, String note) {}
}
