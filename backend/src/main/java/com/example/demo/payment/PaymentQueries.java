package com.example.demo.payment;

import java.util.List;

/** 決済記録の参照を、自モジュールの Controller へ公開する。 */
// 参照のインタフェースであり、ラムダで実装する関数型 interface ではない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PaymentQueries {

  /** 検索条件の注文の決済記録を返す。1 注文につき 1 件までなので、0 件か 1 件を返す。 */
  List<PaymentSummary> search(PaymentSearchCriteria criteria);
}
