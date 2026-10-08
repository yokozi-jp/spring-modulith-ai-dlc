package com.example.demo.payment.presentation.web;

import com.example.demo.payment.PaymentSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 決済記録の一覧の 1 行を返す API の本文。
 *
 * @param paymentId 決済記録の ID
 * @param orderId 決済した注文の ID
 * @param amount 請求した金額
 * @param gatewayPaymentCode 決済代行が採番した決済の識別子
 * @param paidAt 決済した時刻
 */
public record PaymentSummaryResponse(
    @Schema(example = "7c1e4b2a-3d5f-4a6b-8c9d-0e1f2a3b4c5d") String paymentId,
    @Schema(example = "5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f") String orderId,
    @Schema(example = "240.00") BigDecimal amount,
    @Schema(example = "ch_5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f") String gatewayPaymentCode,
    @Schema(example = "2026-10-06T01:02:03.123456Z") Instant paidAt) {

  /** 参照の結果から作る。 */
  public static PaymentSummaryResponse from(final PaymentSummary summary) {
    return new PaymentSummaryResponse(
        summary.paymentId(),
        summary.orderId(),
        summary.amount(),
        summary.gatewayPaymentCode(),
        summary.paidAt());
  }
}
