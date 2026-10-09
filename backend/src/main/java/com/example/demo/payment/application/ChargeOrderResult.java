package com.example.demo.payment.application;

import org.jspecify.annotations.Nullable;

/**
 * 確定した注文の代金を請求するユースケースの結果。
 *
 * @param paymentId 注文の決済記録の ID。注文が確定しておらず請求しなかったときは null
 */
public record ChargeOrderResult(@Nullable String paymentId) {}
