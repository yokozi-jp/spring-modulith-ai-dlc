package com.example.demo.tenancy.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;

/**
 * テナントに所属している利用者の役割を変えるユースケースの入力。
 *
 * @param tenantId 利用者が所属するテナントの ID。選んだテナントから取る
 * @param userId 役割を変える利用者の ID
 * @param role 新しい役割のコード値（ADMIN か MEMBER）
 * @param expectedLockNo 画面が読んだテナントのロック番号
 */
public record ChangeMemberRoleCommand(
    String tenantId, String userId, String role, ExpectedLockNo expectedLockNo)
    implements VersionedCommand {}
