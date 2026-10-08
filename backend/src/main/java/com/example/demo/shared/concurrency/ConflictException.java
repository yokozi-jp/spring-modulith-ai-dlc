package com.example.demo.shared.concurrency;

import java.io.Serial;

/**
 * 読み直せば解消しうる競合を表す。別のリクエストが先に行を更新した場合、行ロックを待ち切れなかった場合、一意制約に違反した場合に投げる。
 *
 * <p>HTTP では 409 Conflict になる。どの競合かは {@link Kind} で区別し、API のログには {@code conflict.kind} として記録する。
 *
 * <p>原因（cause）を持たない。原因の例外のメッセージには SQL と重複したキーの値が入り、ログに出てしまうためである（ADR-062、{@code
 * docs/observability/conventions.md}）。コンストラクタは原因を {@code null} に決めるので、{@link #initCause} は {@link
 * IllegalStateException} になる。
 */
public final class ConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** 競合の種類。 */
  public enum Kind {
    /** 版の不一致。別のリクエストが先に行を更新した。 */
    VERSION,
    /** 行ロックを待ち切れなかった（{@code 55P03}）。 */
    LOCK,
    /** 一意制約に違反した（{@code 23505}）。 */
    UNIQUE
  }

  /** 競合の種類。 */
  private final Kind conflictKind;

  /** 種類とメッセージから競合の例外を作る。原因は持たない。 */
  public ConflictException(final Kind kind, final String message) {
    super(message, null);
    this.conflictKind = kind;
  }

  /** 競合の種類を返す。 */
  public Kind kind() {
    return conflictKind;
  }
}
