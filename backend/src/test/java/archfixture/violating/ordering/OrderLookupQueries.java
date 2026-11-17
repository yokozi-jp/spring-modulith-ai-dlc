package archfixture.violating.ordering;

import java.util.List;
import java.util.Optional;

/** 違反：numericIdsDoNotLeaveModules（公開 API の引数と戻り値に数値の注文 ID を使う）。 */
public interface OrderLookupQueries {

  /** 数値の注文 ID で注文を引く。 */
  OrderSummary findById(long orderId);

  /** 客先注文番号から数値の注文 ID を返す。 */
  Long findOrderId(String customerOrderCode);

  /** 数値の注文 ID の一覧で注文を引く。 */
  List<OrderSummary> findByIds(List<Long> orderIds);

  /** 客先注文番号から数値の注文 ID を返す。見つからなければ空を返す。 */
  Optional<Long> findLatestOrderId(String customerOrderCode);

  /** 客先注文番号から数値の明細 ID の配列を返す。 */
  long[] findLineIds(String customerOrderCode);
}
