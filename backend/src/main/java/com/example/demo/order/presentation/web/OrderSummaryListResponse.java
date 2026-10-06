package com.example.demo.order.presentation.web;

import java.util.List;

/**
 * 注文の一覧を items で包んで返す API の本文。
 *
 * @param items 注文の一覧の行
 */
public record OrderSummaryListResponse(List<OrderSummaryResponse> items) {

  /** 一覧を変更できないリストとして持つ。 */
  public OrderSummaryListResponse {
    items = List.copyOf(items);
  }
}
