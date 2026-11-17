package archfixture.violating.ordering;

/** 違反：numericIdsDoNotLeaveModules（公開 API の引数と戻り値に数値の注文 ID を使う）。 */
public interface OrderLookupQueries {

  /** 数値の注文 ID で注文を引く。 */
  OrderSummary findById(long orderId);

  /** 客先注文番号から数値の注文 ID を返す。 */
  Long findOrderId(String customerOrderCode);
}
