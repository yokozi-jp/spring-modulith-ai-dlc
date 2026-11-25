package com.example.demo.shared.idempotency;

import java.io.Serial;

/**
 * 同じ {@code Idempotency-Key} の要求がまだ処理中で、その終わりを {@code lock_timeout} までに待ち切れなかったことを表す。
 *
 * <p>HTTP では {@code Retry-After} 付きの 409 の {@code /problems/idempotency-key-in-progress}
 * になる。行の版の競合の {@code ConflictException} と違い、クライアントは同じキーで自動で再試行してよい（ADR-126）。
 */
public final class IdempotencyKeyInProgressException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから例外を作る。原因は持たない。 */
  public IdempotencyKeyInProgressException(final String message) {
    super(message, null);
  }
}
