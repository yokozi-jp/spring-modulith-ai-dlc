package com.example.demo.tenancy.presentation.web;

import com.example.demo.tenancy.TenantMembersDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * テナントの所属の一覧を items で包んで返す API の本文。
 *
 * @param lockNo 所属を変える操作の本文で送り返す、テナントのロック番号
 * @param items 所属している利用者。所属した順に並ぶ
 */
public record TenantMembersDetailsResponse(
    @NotNull @Schema(example = "1") long lockNo,
    @NotNull List<TenantMembersDetailsResponse.TenantMemberResponse> items) {

  /** 一覧を変更できないリストとして持つ。 */
  public TenantMembersDetailsResponse {
    items = List.copyOf(items);
  }

  /** 参照の結果から作る。 */
  public static TenantMembersDetailsResponse from(final TenantMembersDetails details) {
    return new TenantMembersDetailsResponse(
        details.lockNo(), details.members().stream().map(TenantMemberResponse::from).toList());
  }

  /**
   * テナントに所属している利用者。
   *
   * @param userId 利用者の ID
   * @param role テナントの中の役割のコード値（ADMIN、MEMBER）
   */
  public record TenantMemberResponse(
      @NotNull @Schema(example = "0b6f2c1e-4d3a-4e5f-8a7b-9c0d1e2f3a4b") UUID userId,
      @NotNull @Schema(example = "MEMBER") String role) {

    /** 参照の結果から作る。 */
    public static TenantMemberResponse from(final TenantMembersDetails.Member member) {
      return new TenantMemberResponse(UUID.fromString(member.userId()), member.role());
    }
  }
}
