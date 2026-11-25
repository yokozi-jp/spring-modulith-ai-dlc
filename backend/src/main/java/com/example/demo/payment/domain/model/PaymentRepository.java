package com.example.demo.payment.domain.model;

import java.util.Optional;

/** 決済記録を保存し、取り出す。 */
public interface PaymentRepository {

  /** 注文の決済記録を返す。なければ空を返す。 */
  Optional<Payment> findByOrderId(OrderId orderId);

  /**
   * 注文の決済記録がなければ、新しい決済記録を保存する。
   *
   * <p>同じ注文の決済記録を書いて未コミットのトランザクションがあれば、その終わりまで待つ。
   *
   * @return 保存したら {@code true}、注文の決済記録が既にあって保存しなかったら {@code false}
   */
  boolean add(Payment payment);
}
