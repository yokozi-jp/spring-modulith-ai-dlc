package com.example.demo.shared.idempotency;

import java.io.Serial;

/**
 * 有効な {@code Idempotency-Key} を、内容の違う要求に使ったことを表す。クライアントの実装の誤りである。
 *
 * <p>HTTP では 400 の {@code /problems/idempotency-key-payload-mismatch} になる（ADR-126）。
 */
public final class IdempotencyKeyPayloadMismatchException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから例外を作る。原因は持たない。 */
  public IdempotencyKeyPayloadMismatchException(final String message) {
    super(message, null);
  }
}
