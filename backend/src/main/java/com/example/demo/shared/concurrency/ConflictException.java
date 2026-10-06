package com.example.demo.shared.concurrency;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * 読み直せば解消しうる競合を表す。別のリクエストが先に行を更新した場合、行ロックを待ち切れなかった場合、一意制約に違反した場合に投げる。
 *
 * <p>HTTP では 409 Conflict になる。行ロックは {@code CannotAcquireLockException}、一意制約は {@code
 * DuplicateKeyException} を原因に持つ。
 */
public final class ConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから競合の例外を作る。 */
  public ConflictException(final String message) {
    super(message);
  }

  /** メッセージと原因から競合の例外を作る。 */
  public ConflictException(final String message, final @Nullable Throwable cause) {
    super(message, cause);
  }
}
