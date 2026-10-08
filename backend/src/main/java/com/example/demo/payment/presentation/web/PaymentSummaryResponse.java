package com.example.demo.payment.presentation.web;

import com.example.demo.payment.PaymentSummary;
import io.swagger.v3.oas.annotations.media.Schema;
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
 * @param paidAt 決済した時刻。status が PAID のときだけ返す
 */
public record PaymentSummaryResponse(
    @Schema(example = "7c1e4b2a-3d5f-4a6b-8c9d-0e1f2a3b4c5d") String paymentId,
    @Schema(example = "5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f") String orderId,
    @Schema(example = "240.00") BigDecimal amount,
    @Schema(example = "PAID") String status,
    @Schema(example = "ch_5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f")
        @Nullable String gatewayPaymentCode,
    @Schema(example = "2026-10-06T01:02:03.123456Z") @Nullable Instant paidAt) {

  /** 請求を受け付けた結果のコード値。 */
  private static final String PAID = "PAID";

  /** 参照の結果から作る。決済した時刻は、請求を受け付けた結果にだけ入れる。 */
  public static PaymentSummaryResponse from(final PaymentSummary summary) {
    return new PaymentSummaryResponse(
        summary.paymentId(),
        summary.orderId(),
        summary.amount(),
        summary.status(),
        summary.gatewayPaymentCode(),
        PAID.equals(summary.status()) ? summary.recordedAt() : null);
  }
}
