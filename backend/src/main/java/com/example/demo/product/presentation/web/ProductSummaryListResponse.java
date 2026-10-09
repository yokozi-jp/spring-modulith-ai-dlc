package com.example.demo.product.presentation.web;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 商品の一覧を items で包んで返す API の本文。
 *
 * @param items 商品の一覧の行
 */
public record ProductSummaryListResponse(@NotNull List<ProductSummaryResponse> items) {

  /** 一覧を変更できないリストとして持つ。 */
  public ProductSummaryListResponse {
    items = List.copyOf(items);
  }
}
