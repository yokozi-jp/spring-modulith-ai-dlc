package com.example.demo.payment;

import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 決済記録の一覧の 1 行の参照の結果。
 *
 * @param paymentId 決済記録の ID
 * @param orderId 請求した注文の ID
 * @param amount 請求した金額
 * @param status 請求の結果（{@code PAID}、{@code DECLINED}、{@code FAILED}）
 * @param gatewayPaymentCode 決済代行が採番した決済の識別子。採番されなかったときは null
 * @param recordedAt 請求の結果を記録した時刻
 */
public record PaymentSummary(
    String paymentId,
    String orderId,
    BigDecimal amount,
    String status,
    @Nullable String gatewayPaymentCode,
    Instant recordedAt) {}
