package com.example.demo.tenancy.presentation.web;

import com.example.demo.tenancy.TenantMembersDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * テナントに所属している利用者を返す API の本文。
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
