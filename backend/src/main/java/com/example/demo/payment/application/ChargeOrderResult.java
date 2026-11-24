package com.example.demo.payment.application;

/** 確定した注文の代金を請求するユースケースの結果。 */
public sealed interface ChargeOrderResult {

  /**
   * 請求して決済記録を保存した。
   *
   * @param paymentId 保存した決済記録の ID
   */
  record Charged(String paymentId) implements ChargeOrderResult {}

  /**
   * 注文の決済記録がすでにあり、請求しなかった。
   *
   * @param paymentId すでにある決済記録の ID
   */
  record AlreadyRecorded(String paymentId) implements ChargeOrderResult {}

  /** 注文が確定しておらず、請求しなかった。決済記録は作らない。 */
  record NotCharged() implements ChargeOrderResult {}
}
