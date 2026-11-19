package com.example.demo.tenancy.presentation.web;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 利用者の所属の一覧を items で包んで返す API の本文。
 *
 * @param items 所属の一覧の行
 */
public record MembershipListResponse(@NotNull List<MembershipResponse> items) {

  /** 一覧を変更できないリストとして持つ。 */
  public MembershipListResponse {
    items = List.copyOf(items);
  }
}
