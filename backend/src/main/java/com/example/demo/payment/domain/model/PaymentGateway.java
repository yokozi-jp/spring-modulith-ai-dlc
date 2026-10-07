package com.example.demo.payment.domain.model;

/** 外部の決済代行。 */
// 外部システムの interface であり、ラムダで実装する関数型 interface ではない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PaymentGateway {

  /**
   * 注文の代金を請求し、決済代行が採番した決済の識別子を返す。
   *
   * <p>注文 ID を冪等キーにする。同じ注文 ID の二回目以降の請求では、決済代行は新たに請求せず、最初の請求の識別子を返す。
   */
  GatewayPaymentCode charge(OrderId orderId, Money amount);
}
