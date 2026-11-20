package com.example.demo.tenancy.domain.model;

import com.example.demo.shared.concurrency.Versioned;
import java.util.List;
import java.util.UUID;

/**
 * 利用者の集約ルート。アプリが採番した ID を持ち、IdP の識別（{@link ExternalIdentity}）を 1 つ以上対応づける。
 *
 * <p>IdP を移るときは、対応づけだけを書き換える。利用者 ID は変わらない。
 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class User implements Versioned {

  /** 利用者 ID。 */
  private final UserId id;

  /** 対応づけた IdP の識別。1 つ以上ある。 */
  private final List<ExternalIdentity> identities;

  /** 楽観的ロックのロック番号。 */
  private final long lockNo;

  private User(final UserId id, final List<ExternalIdentity> identities, final long lockNo) {
    if (identities.isEmpty()) {
      throw new IllegalArgumentException(
          "user must have an external identity: userId=" + id.value());
    }
    this.id = id;
    this.identities = List.copyOf(identities);
    this.lockNo = lockNo;
  }

  /** IdP の識別を対応づけた新しい利用者を作る。利用者 ID は UUID v4 で採番する（ADR-060）。 */
  public static User register(final ExternalIdentity identity) {
    return new User(new UserId(UUID.randomUUID()), List.of(identity), 1L);
  }

  /** 保存済みの利用者を復元する。Repository の実装が使う。 */
  public static User restore(
      final UserId id, final List<ExternalIdentity> identities, final long lockNo) {
    return new User(id, identities, lockNo);
  }

  /** 利用者 ID を返す。 */
  public UserId id() {
    return id;
  }

  /** 対応づけた IdP の識別を返す。 */
  public List<ExternalIdentity> identities() {
    return identities;
  }

  /** ロック番号を返す。 */
  @Override
  public long lockNo() {
    return lockNo;
  }
}
