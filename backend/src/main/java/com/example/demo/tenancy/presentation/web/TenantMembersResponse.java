package com.example.demo.tenancy.presentation.web;

import com.example.demo.tenancy.TenantMembersDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * テナントの所属の一覧を items で包んで返す API の本文。
 *
 * @param lockNo 所属を変える操作の本文で送り返す、テナントのロック番号
 * @param items 所属している利用者。所属した順に並ぶ
 */
public record TenantMembersResponse(
    @NotNull @Schema(example = "1") long lockNo, @NotNull List<TenantMemberResponse> items) {

  /** 一覧を変更できないリストとして持つ。 */
  public TenantMembersResponse {
    items = List.copyOf(items);
  }

  /** 参照の結果から作る。 */
  public static TenantMembersResponse from(final TenantMembersDetails details) {
    return new TenantMembersResponse(
        details.lockNo(), details.members().stream().map(TenantMemberResponse::from).toList());
  }
}
