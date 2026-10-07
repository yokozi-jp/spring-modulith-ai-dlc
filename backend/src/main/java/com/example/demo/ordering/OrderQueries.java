package com.example.demo.ordering;

import java.util.List;
import java.util.Optional;

/** 注文の参照を、自モジュールの Controller と他モジュールへ公開する。 */
public interface OrderQueries {

  /** 注文の詳細を返す。注文がなければ空を返す。 */
  Optional<OrderDetails> findDetails(String orderId);

  /** 検索条件に合う注文の一覧を、作成した時刻の新しい順に返す。 */
  List<OrderSummary> search(OrderSearchCriteria criteria);
}
