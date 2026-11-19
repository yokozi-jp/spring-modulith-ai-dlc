package com.example.demo.tenancy.presentation.web;

import com.example.demo.tenancy.MembershipSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * 利用者のテナントへの所属を返す API の本文。
 *
 * @param tenantId 所属するテナントの ID
 * @param tenantName 所属するテナントの名前
 */
public record MembershipResponse(
    @NotNull @Schema(example = "6f1c2b3a-4d5e-4f60-8a7b-9c0d1e2f3a4b") UUID tenantId,
    @NotNull @Schema(example = "株式会社サンプル") String tenantName) {

  /** 参照の結果から作る。 */
  public static MembershipResponse from(final MembershipSummary summary) {
    return new MembershipResponse(UUID.fromString(summary.tenantId()), summary.tenantName());
  }
}
