package archfixture.conforming.order;

import java.util.List;
import java.util.Optional;

/** 他モジュールへ公開する注文の読み取り窓口。 */
public interface OrderQueries {

  /** 注文の詳細を返す。 */
  Optional<OrderDetails> findDetails(String orderId);

  /** 条件に合う注文の一覧を返す。 */
  List<OrderSummary> search(OrderSearchCriteria criteria);
}
