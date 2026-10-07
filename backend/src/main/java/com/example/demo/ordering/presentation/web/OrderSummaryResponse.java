package com.example.demo.ordering.presentation.web;

import com.example.demo.ordering.OrderSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 注文の一覧の1行を返す API の本文。
 *
 * @param orderId 注文の ID
 * @param customerOrderCode 客先注文番号
 * @param status 注文の状態のコード値（DRAFT、CONFIRMED、CANCELLED）
 * @param orderedAt 注文を作成した時刻
 * @param totalAmount 明細の金額の合計
 * @param lockNo 更新の本文で送り返すロック番号
 */
public record OrderSummaryResponse(
    @Schema(example = "5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f") String orderId,
    @Schema(example = "C-2026-0001") String customerOrderCode,
    @Schema(example = "DRAFT") String status,
    @Schema(example = "2026-10-05T01:02:03.123456Z") Instant orderedAt,
    @Schema(example = "240.00") BigDecimal totalAmount,
    @Schema(example = "1") long lockNo) {

  /** 参照の結果から作る。 */
  public static OrderSummaryResponse from(final OrderSummary summary) {
    return new OrderSummaryResponse(
        summary.orderId(),
        summary.customerOrderCode(),
        summary.status(),
        summary.orderedAt(),
        summary.totalAmount(),
        summary.lockNo());
  }
}
