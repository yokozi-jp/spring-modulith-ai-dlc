package com.example.demo.tenancy.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;

/**
 * テナントから利用者の所属を外すユースケースの入力。
 *
 * @param tenantId 利用者が所属するテナントの ID。選んだテナントから取る
 * @param userId 所属を外す利用者の ID
 * @param expectedLockNo 画面が読んだテナントのロック番号
 */
public record RemoveMemberCommand(String tenantId, String userId, ExpectedLockNo expectedLockNo)
    implements VersionedCommand {}
