package com.example.demo.payment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 決済記録の一覧の 1 行の参照の結果。
 *
 * @param paymentId 決済記録の ID
 * @param orderId 決済した注文の ID
 * @param amount 請求した金額
 * @param gatewayPaymentCode 決済代行が採番した決済の識別子
 * @param paidAt 決済した時刻
 */
public record PaymentSummary(
    String paymentId,
    String orderId,
    BigDecimal amount,
    String gatewayPaymentCode,
    Instant paidAt) {}
