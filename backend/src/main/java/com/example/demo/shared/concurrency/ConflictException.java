package com.example.demo.shared.concurrency;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * 楽観的ロックの競合を表す。別のリクエストが先に行を更新した場合と、行ロックを待ち切れなかった場合に投げる。
 *
 * <p>HTTP では 409 Conflict になる。行ロックを待ち切れなかった場合は、原因に {@code CannotAcquireLockException} を持つ。
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
