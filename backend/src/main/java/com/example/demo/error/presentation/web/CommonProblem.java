package com.example.demo.error.presentation.web;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.idempotency.IdempotencyKeyException;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * モジュールによらない problem type。type と title のキーは、定数の名前の kebab-case から {@link ProblemTypes} が作る（ADR-127）。
 */
enum CommonProblem {
  /** 入力検証エラー。拡張メンバー errors に誤りを入れる（ADR-058）。 */
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
  /** {@code Idempotency-Key} のヘッダーがない（ADR-126）。 */
  IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST),
  /** {@code Idempotency-Key} のヘッダーが 1 行の、引用符で囲んだ UUID でない（ADR-126）。 */
  IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST),
  /** 有効な {@code Idempotency-Key} を、内容の違う要求に使った（ADR-126）。 */
  IDEMPOTENCY_KEY_PAYLOAD_MISMATCH(HttpStatus.BAD_REQUEST),
  /** CSRF トークンの検証失敗。 */
  CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN),
  /** 別のオリジンから来た更新系の要求を、CSRF トークンを確かめる前に拒否した（ADR-136）。 */
  CROSS_ORIGIN_REQUEST_REJECTED(HttpStatus.FORBIDDEN),
  /** テナントを選ぶ前に業務の API を呼んだ。 */
  TENANT_NOT_SELECTED(HttpStatus.FORBIDDEN),
  /** 版の不一致。別の要求が先に行を更新した。 */
  VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** 行ロックを待ち切れなかった（{@code 55P03}）。 */
  LOCK_TIMEOUT(HttpStatus.CONFLICT),
  /** 一意制約に違反した（{@code 23505}）。 */
  UNIQUE_VIOLATION(HttpStatus.CONFLICT),
  /**
   * 同じ {@code Idempotency-Key} の要求がまだ処理中である。版の競合と違い、クライアントは同じキーで再試行してよい（ADR-126）。
   *
   * <p>再試行までの秒数は、ADR-055 の {@code lock_timeout} の待ちと同じ桁にする。
   */
  IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatus.CONFLICT, Duration.ofSeconds(1));

  /** 応答の HTTP status。 */
  private final HttpStatus httpStatus;

  /** 応答の {@code Retry-After}。付けない problem では null。 */
  private final @Nullable Duration retryAfterDelay;

  CommonProblem(final HttpStatus httpStatus) {
    this(httpStatus, null);
  }

  CommonProblem(final HttpStatus httpStatus, final @Nullable Duration retryAfter) {
    this.httpStatus = httpStatus;
    this.retryAfterDelay = retryAfter;
  }

  /** 応答の HTTP status を返す。 */
  /* package */ HttpStatus status() {
    return httpStatus;
  }

  /** 応答に {@code Retry-After} を付ける problem なら、再試行までの時間を返す。 */
  /* package */ Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfterDelay);
  }

  /** 競合の種類の problem を返す。{@code Kind} を足すと、網羅した switch がコンパイルを止める。 */
  /* package */ static CommonProblem forConflict(final ConflictException.Kind kind) {
    return switch (kind) {
      case VERSION -> VERSION_CONFLICT;
      case LOCK -> LOCK_TIMEOUT;
      case UNIQUE -> UNIQUE_VIOLATION;
      case IDEMPOTENCY_IN_PROGRESS -> IDEMPOTENCY_KEY_IN_PROGRESS;
    };
  }

  /** {@code Idempotency-Key} の誤りの problem を返す。{@code Reason} を足すと、網羅した switch がコンパイルを止める。 */
  /* package */ static CommonProblem forIdempotencyKey(
      final IdempotencyKeyException.Reason reason) {
    return switch (reason) {
      case MISSING -> IDEMPOTENCY_KEY_MISSING;
      case INVALID -> IDEMPOTENCY_KEY_INVALID;
      case PAYLOAD_MISMATCH -> IDEMPOTENCY_KEY_PAYLOAD_MISMATCH;
    };
  }
}
