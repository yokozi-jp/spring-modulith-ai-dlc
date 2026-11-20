package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.tenancy.application.RemoveMemberCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * テナントから利用者の所属を外す API の入力。DELETE は本文を持たないため、ロック番号はクエリパラメータで受ける。
 *
 * @param lockNo 画面が読んだテナントのロック番号
 */
public record RemoveMemberRequest(@NotNull @Min(1) @Schema(example = "1") long lockNo) {

  /** 選んだテナントの ID とパス変数の利用者の ID と合わせて Command へ変換する。 */
  public RemoveMemberCommand toCommand(final String tenantId, final UUID userId) {
    return new RemoveMemberCommand(tenantId, userId.toString(), new ExpectedLockNo(lockNo));
  }
}
