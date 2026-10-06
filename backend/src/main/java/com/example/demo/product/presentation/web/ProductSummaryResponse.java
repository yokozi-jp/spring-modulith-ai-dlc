package com.example.demo.product.presentation.web;

import com.example.demo.product.ProductSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * 商品の一覧の1行を返す API の本文。
 *
 * @param productId 商品の ID
 * @param productCode 商品コード
 * @param productName 商品名
 * @param unitPrice 単価
 * @param salesStatus 販売の状態のコード値（ON_SALE か DISCONTINUED）
 */
public record ProductSummaryResponse(
    @Schema(example = "0b6c8f6e-2f4a-4c1e-9d3b-7a1e5c2d4f60") String productId,
    @Schema(example = "P-0001") String productCode,
    @Schema(example = "ボールペン") String productName,
    @Schema(example = "120.00") BigDecimal unitPrice,
    @Schema(example = "ON_SALE") String salesStatus) {

  /** 参照の結果から作る。 */
  public static ProductSummaryResponse from(final ProductSummary summary) {
    return new ProductSummaryResponse(
        summary.productId(),
        summary.productCode(),
        summary.productName(),
        summary.unitPrice(),
        summary.salesStatus());
  }
}
