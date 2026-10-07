package com.example.demo.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 注文の詳細の参照の結果。
 *
 * @param orderId 注文 ID
 * @param customerOrderCode 客先注文番号
 * @param status 注文の状態（{@code DRAFT}、{@code CONFIRMED}、{@code CANCELLED}）
 * @param orderedAt 注文を作成した時刻
 * @param totalAmount 明細の金額の合計
 * @param lockNo 更新の本文で送り返すロック番号
 * @param lines 注文の明細
 */
public record OrderDetails(
    String orderId,
    String customerOrderCode,
    String status,
    Instant orderedAt,
    BigDecimal totalAmount,
    long lockNo,
    List<OrderDetails.Line> lines) {

  /** 明細を変更できないリストとして持つ。 */
  public OrderDetails {
    lines = List.copyOf(lines);
  }

  /**
   * 注文の明細の1行。
   *
   * @param lineNumber 明細の番号（1 から）
   * @param productId 商品 ID
   * @param quantity 数量
   * @param unitPrice 作成時に読んだ単価
   * @param amount 単価と数量の積
   */
  public record Line(
      int lineNumber, String productId, int quantity, BigDecimal unitPrice, BigDecimal amount) {}
}
