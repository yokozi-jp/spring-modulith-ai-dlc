package com.example.demo.tenancy.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotentCommand;

/**
 * テナントから利用者の所属を外すユースケースの入力。
 *
 * @param tenantId 利用者が所属するテナントの ID。選んだテナントから取る
 * @param userId 所属を外す利用者の ID
 * @param expectedLockNo 画面が読んだテナントのロック番号
 * @param idempotencyKey クライアントが所属の取り外しの操作ごとに採番したキー
 */
public record RemoveMemberCommand(
    String tenantId, String userId, ExpectedLockNo expectedLockNo, IdempotencyKey idempotencyKey)
    implements VersionedCommand, IdempotentCommand {}
