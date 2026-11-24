package com.example.demo.ordering.domain.model;

import com.example.demo.shared.failure.FailureReason;

/** 注文の業務規則の違反の理由。定数ごとに 422 の problem type と title が決まる（ADR-125）。 */
public enum OrderFailure implements FailureReason {
  /** 下書きでない注文を変更、確定、取消しようとした。 */
  NOT_DRAFT,
  /** 存在しない商品を指定した。 */
  PRODUCT_NOT_FOUND,
  /** 販売を終了した商品を指定した。 */
  PRODUCT_DISCONTINUED,
  /** 明細がない。 */
  NO_LINES
}
