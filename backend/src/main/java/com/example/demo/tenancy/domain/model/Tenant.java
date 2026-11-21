package com.example.demo.tenancy.domain.model;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.Versioned;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.tenant.TenantId;
import java.util.List;
import java.util.Locale;

/**
 * テナントの集約ルート。所属している利用者と役割を持ち、所属の変更を通して、管理者が 1 人以上いることを守る。
 *
 * <p>管理者が 1 人もいないテナントも復元できる。守るのは、管理者を 0 人にする操作の拒否だけである。
 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class Tenant implements Versioned {

  /** テナント ID。 */
  private final TenantId id;

  /** 楽観的ロックのロック番号。所属の変更でも進む。 */
  private final long lockNo;

  /** 所属している利用者。所属した順に並ぶ。 */
  private List<TenantMember> members;

  private Tenant(final TenantId id, final List<TenantMember> members, final long lockNo) {
    this.id = id;
    this.members = List.copyOf(members);
    this.lockNo = lockNo;
  }

  /** 保存済みのテナントを復元する。Repository の実装が使う。 */
  public static Tenant restore(
      final TenantId id, final List<TenantMember> members, final long lockNo) {
    return new Tenant(id, members, lockNo);
  }

  /**
   * 所属している利用者の役割を変える。
   *
   * @param userId 役割を変える利用者
   * @param role 新しい役割
   * @throws NotFoundException 利用者がこのテナントに所属していない場合
   * @throws BusinessRuleViolationException 最後の管理者の役割を下げる場合
   */
  public void changeRole(final UserId userId, final Role role) {
    requireMember(userId);
    replaceMembers(
        userId,
        members.stream()
            .map(member -> member.userId().equals(userId) ? new TenantMember(userId, role) : member)
            .toList());
  }

  /**
   * 所属している利用者を外す。
   *
   * @param userId 外す利用者
   * @throws NotFoundException 利用者がこのテナントに所属していない場合
   * @throws BusinessRuleViolationException 最後の管理者を外す場合
   */
  public void removeMember(final UserId userId) {
    requireMember(userId);
    replaceMembers(
        userId, members.stream().filter(member -> !member.userId().equals(userId)).toList());
  }

  /** テナント ID を返す。 */
  public TenantId id() {
    return id;
  }

  /** 所属している利用者を、所属した順に返す。 */
  public List<TenantMember> members() {
    return List.copyOf(members);
  }

  /** ロック番号を返す。 */
  @Override
  public long lockNo() {
    return lockNo;
  }

  /**
   * 画面が読んだテナントのロック番号が、保存済みのテナントのロック番号と一致することを確かめる。
   *
   * @throws ConflictException 一致しない場合
   */
  public void ensureLockNo(final ExpectedLockNo expectedLockNo) {
    if (lockNo != expectedLockNo.value()) {
      throw new ConflictException(
          ConflictException.Kind.VERSION,
          String.format(
              Locale.ROOT,
              "lock number mismatch: tenantId=%s, expectedLockNo=%d, lockNo=%d",
              id.value(),
              expectedLockNo.value(),
              lockNo));
    }
  }

  private void requireMember(final UserId userId) {
    if (members.stream().noneMatch(member -> member.userId().equals(userId))) {
      throw new NotFoundException(
          "member not found: tenantId=" + id.value() + ", userId=" + userId.value());
    }
  }

  /** 置き換えると管理者がいなくなる場合は拒否する。target は例外のメッセージに入れる操作の対象。 */
  private void replaceMembers(final UserId target, final List<TenantMember> next) {
    if (hasAdmin(members) && !hasAdmin(next)) {
      throw new BusinessRuleViolationException(
          "tenant must keep an admin: tenantId=" + id.value() + ", userId=" + target.value());
    }
    members = List.copyOf(next);
  }

  private static boolean hasAdmin(final List<TenantMember> members) {
    return members.stream().anyMatch(member -> member.role() == Role.ADMIN);
  }
}
