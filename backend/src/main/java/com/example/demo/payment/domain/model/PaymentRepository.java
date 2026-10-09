package com.example.demo.payment.domain.model;

import com.example.demo.shared.concurrency.ConflictException;
import java.util.Optional;

/** 決済記録を保存し、取り出す。 */
public interface PaymentRepository {

  /** 注文の決済記録を返す。なければ空を返す。 */
  Optional<Payment> findByOrderId(OrderId orderId);

  /**
   * 新しい決済記録を保存する。
   *
   * @throws ConflictException 注文の決済記録が既にある場合
   */
  void add(Payment payment);
}
