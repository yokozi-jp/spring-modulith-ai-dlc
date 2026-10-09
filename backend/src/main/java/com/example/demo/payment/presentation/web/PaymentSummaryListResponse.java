package com.example.demo.payment.presentation.web;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 決済記録の一覧を items で包んで返す API の本文。
 *
 * @param items 決済記録の一覧の行。1 注文につき 0 件か 1 件
 */
public record PaymentSummaryListResponse(@NotNull List<PaymentSummaryResponse> items) {

  /** 一覧を変更できないリストとして持つ。 */
  public PaymentSummaryListResponse {
    items = List.copyOf(items);
  }
}
