package com.example.demo.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 注文の一覧の1行の参照の結果。
 *
 * @param orderId 注文 ID
 * @param customerOrderCode 客先注文番号
 * @param status 注文の状態（{@code DRAFT}、{@code CONFIRMED}、{@code CANCELLED}）
 * @param orderedAt 注文を作成した時刻
 * @param totalAmount 明細の金額の合計
 * @param lockNo 更新の本文で送り返すロック番号
 */
public record OrderSummary(
    String orderId,
    String customerOrderCode,
    String status,
    Instant orderedAt,
    BigDecimal totalAmount,
    long lockNo) {}
