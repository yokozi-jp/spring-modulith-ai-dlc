package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.tenancy.application.ChangeMemberRoleCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * テナントに所属している利用者の役割を変える API の本文。
 *
 * @param role 新しい役割のコード値（ADMIN、MEMBER）
 * @param lockNo 画面が読んだテナントのロック番号
 */
public record ChangeMemberRoleRequest(
    @NotNull @Pattern(regexp = "^(ADMIN|MEMBER)$") @Schema(example = "MEMBER") String role,
    @NotNull @Min(1) @Schema(example = "1") long lockNo) {

  /** 選んだテナントの ID とパス変数の利用者の ID と合わせて Command へ変換する。 */
  public ChangeMemberRoleCommand toCommand(final String tenantId, final UUID userId) {
    return new ChangeMemberRoleCommand(
        tenantId, userId.toString(), role, new ExpectedLockNo(lockNo));
  }
}
