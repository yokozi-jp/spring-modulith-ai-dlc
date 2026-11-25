package com.example.demo.product.domain.model;

import com.example.demo.shared.failure.FailureReason;

/** 商品の業務規則の違反の理由。定数ごとに 422 の problem type と title が決まる（ADR-127）。 */
public enum ProductFailure implements FailureReason {
  /** 商品コードが空である。 */
  BLANK_PRODUCT_CODE,
  /** 商品名が空である。 */
  BLANK_PRODUCT_NAME,
  /** 単価が 0.01 円より小さい。 */
  UNIT_PRICE_BELOW_MINIMUM
}
