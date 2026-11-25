package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.tenancy.application.RemoveMemberCommand;
import java.util.UUID;

/**
 * テナントから利用者の所属を外す API の入力。DELETE は本文を持たないため、クエリパラメータのロック番号を Controller が包む。
 *
 * @param lockNo 画面が読んだテナントのロック番号
 */
public record RemoveMemberRequest(long lockNo) {

  /** 選んだテナントの ID、パス変数の利用者の ID、ヘッダーの冪等性キーと合わせて Command へ変換する。 */
  public RemoveMemberCommand toCommand(
      final String tenantId, final UUID userId, final IdempotencyKey idempotencyKey) {
    return new RemoveMemberCommand(
        tenantId, userId.toString(), new ExpectedLockNo(lockNo), idempotencyKey);
  }
}
