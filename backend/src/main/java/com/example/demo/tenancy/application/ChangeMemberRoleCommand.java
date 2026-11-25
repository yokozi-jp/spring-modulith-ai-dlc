package com.example.demo.tenancy.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotentCommand;

/**
 * テナントに所属している利用者の役割を変えるユースケースの入力。
 *
 * @param userId 役割を変える利用者の ID
 * @param role 新しい役割のコード値（ADMIN か MEMBER）
 * @param expectedLockNo 画面が読んだテナントのロック番号
 * @param idempotencyKey クライアントが役割の変更の操作ごとに採番したキー
 */
public record ChangeMemberRoleCommand(
    String userId, String role, ExpectedLockNo expectedLockNo, IdempotencyKey idempotencyKey)
    implements VersionedCommand, IdempotentCommand {}
