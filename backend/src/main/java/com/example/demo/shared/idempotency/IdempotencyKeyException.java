package com.example.demo.shared.idempotency;

import java.io.Serial;

/**
 * {@code Idempotency-Key} のクライアントの誤りを表す。どの誤りかは {@link Reason} で区別する。
 *
 * <p>HTTP では、理由ごとの type の 400 になる（ADR-126）。
 */
public final class IdempotencyKeyException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** 誤りの理由。 */
  public enum Reason {
    /** ヘッダーがない。 */
    MISSING,
    /** ヘッダーが 1 行の、引用符で囲んだ UUID でない。 */
    INVALID,
    /** 有効なキーを、内容の違う要求に使った。 */
    PAYLOAD_MISMATCH
  }

  /** 誤りの理由。 */
  private final Reason failureReason;

  /** 理由とログ用のメッセージから例外を作る。 */
  public IdempotencyKeyException(final Reason reason, final String message) {
    super(message);
    this.failureReason = reason;
  }

  /** 誤りの理由を返す。 */
  public Reason reason() {
    return failureReason;
  }
}
