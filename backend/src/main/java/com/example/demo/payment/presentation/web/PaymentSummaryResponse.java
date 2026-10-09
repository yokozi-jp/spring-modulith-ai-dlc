package com.example.demo.payment.presentation.web;

import com.example.demo.payment.PaymentSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 決済記録の一覧の 1 行を返す API の本文。
 *
 * @param paymentId 決済記録の ID
 * @param orderId 請求した注文の ID
 * @param amount 請求した金額
 * @param status 請求の結果のコード値（PAID、DECLINED、FAILED）
 * @param gatewayPaymentCode 決済代行が採番した決済の識別子。採番されなかったときは省く
 * @param recordedAt 請求の結果を記録した時刻。status が意味を決める
 */
public record PaymentSummaryResponse(
    @NotNull @Schema(example = "7c1e4b2a-3d5f-4a6b-8c9d-0e1f2a3b4c5d") String paymentId,
    @NotNull @Schema(example = "5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f") String orderId,
    @NotNull @Schema(example = "240.00") BigDecimal amount,
    @NotNull @Schema(example = "PAID") String status,
    @Schema(example = "ch_5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f")
        @Nullable String gatewayPaymentCode,
    @NotNull @Schema(example = "2026-10-06T01:02:03.123456Z") Instant recordedAt) {

  /** 参照の結果から作る。 */
  public static PaymentSummaryResponse from(final PaymentSummary summary) {
    return new PaymentSummaryResponse(
        summary.paymentId(),
        summary.orderId(),
        summary.amount(),
        summary.status(),
        summary.gatewayPaymentCode(),
        summary.recordedAt());
  }
}
