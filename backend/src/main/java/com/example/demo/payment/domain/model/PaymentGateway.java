package com.example.demo.payment.domain.model;

/** 外部の決済代行。 */
// 外部システムの interface であり、ラムダで実装する関数型 interface ではない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PaymentGateway {

  /**
   * 注文の代金を請求し、業務の状態に記録する結果を返す。
   *
   * <p>注文 ID を冪等キーにする。同じ注文 ID の二回目以降の請求では、決済代行は新たに請求せず、最初の請求の結果を返す。
   *
   * <p>決済代行の拒否と契約の不備は結果で返す。一時障害と資格情報の不備は例外を投げ、再投入でやり直せるようにする（ADR-072）。
   */
  ChargeOutcome charge(OrderId orderId, Money amount);
}
