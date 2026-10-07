package com.example.demo.ordering.presentation.web;

import com.example.demo.ordering.OrderDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 注文の詳細を返す API の本文。
 *
 * @param orderId 注文の ID
 * @param customerOrderCode 客先注文番号
 * @param status 注文の状態のコード値（DRAFT、CONFIRMED、CANCELLED）
 * @param orderedAt 注文を作成した時刻
 * @param totalAmount 明細の金額の合計
 * @param lockNo 更新の本文で送り返すロック番号
 * @param lines 注文の明細
 */
public record OrderDetailsResponse(
    @Schema(example = "5f0c2a8e-7d1b-4c3a-9e6f-1a2b3c4d5e6f") String orderId,
    @Schema(example = "C-2026-0001") String customerOrderCode,
    @Schema(example = "DRAFT") String status,
    @Schema(example = "2026-10-05T01:02:03.123456Z") Instant orderedAt,
    @Schema(example = "240.00") BigDecimal totalAmount,
    @Schema(example = "1") long lockNo,
    List<OrderDetailsResponse.OrderLineResponse> lines) {

  /** 明細を変更できないリストとして持つ。 */
  public OrderDetailsResponse {
    lines = List.copyOf(lines);
  }

  /** 参照の結果から作る。 */
  public static OrderDetailsResponse from(final OrderDetails details) {
    return new OrderDetailsResponse(
        details.orderId(),
        details.customerOrderCode(),
        details.status(),
        details.orderedAt(),
        details.totalAmount(),
        details.lockNo(),
        details.lines().stream().map(OrderLineResponse::from).toList());
  }

  /**
   * 注文の明細の1行。
   *
   * @param lineNumber 明細の番号（1 から）
   * @param productId 商品の ID
   * @param quantity 注文した数量
   * @param unitPrice 作成時に読んだ単価
   * @param amount 単価と数量の積
   */
  public record OrderLineResponse(
      @Schema(example = "1") int lineNumber,
      @Schema(example = "0b6c8f6e-2f4a-4c1e-9d3b-7a1e5c2d4f60") String productId,
      @Schema(example = "2") int quantity,
      @Schema(example = "120.00") BigDecimal unitPrice,
      @Schema(example = "240.00") BigDecimal amount) {

    /** 参照の結果の明細から作る。 */
    public static OrderLineResponse from(final OrderDetails.Line line) {
      return new OrderLineResponse(
          line.lineNumber(), line.productId(), line.quantity(), line.unitPrice(), line.amount());
    }
  }
}
