package com.example.demo.product;

import java.math.BigDecimal;

/**
 * 商品の参照の結果。
 *
 * @param productId 商品 ID
 * @param productCode 商品コード
 * @param productName 商品名
 * @param unitPrice 単価
 * @param salesStatus 販売の状態（{@code ON_SALE} か {@code DISCONTINUED}）
 */
public record ProductSummary(
    String productId,
    String productCode,
    String productName,
    BigDecimal unitPrice,
    String salesStatus) {}
