package com.example.demo.payment.application;

/**
 * 確定した注文の代金を請求するユースケースの結果。
 *
 * @param paymentId 注文の決済記録の ID
 */
public record ChargeOrderResult(String paymentId) {}
