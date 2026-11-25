package com.example.demo.error.presentation.web;

import com.example.demo.shared.concurrency.ConflictException;
import org.springframework.http.HttpStatus;

/**
 * モジュールによらない problem type。type と title のキーは、定数の名前の kebab-case から {@link ProblemTypes} が作る（ADR-125）。
 */
enum CommonProblem {
  /** 入力検証エラー。拡張メンバー errors に誤りを入れる（ADR-058）。 */
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
  /** CSRF トークンの検証失敗。 */
  CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN),
  /** テナントを選ぶ前に業務の API を呼んだ。 */
  TENANT_NOT_SELECTED(HttpStatus.FORBIDDEN),
  /** 版の不一致。別の要求が先に行を更新した。 */
  VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** 行ロックを待ち切れなかった（{@code 55P03}）。 */
  LOCK_TIMEOUT(HttpStatus.CONFLICT),
  /** 一意制約に違反した（{@code 23505}）。 */
  UNIQUE_VIOLATION(HttpStatus.CONFLICT);

  /** 応答の HTTP status。 */
  private final HttpStatus httpStatus;

  CommonProblem(final HttpStatus httpStatus) {
    this.httpStatus = httpStatus;
  }

  /** 応答の HTTP status を返す。 */
  /* package */ HttpStatus status() {
    return httpStatus;
  }

  /** 競合の種類の problem を返す。{@code Kind} を足すと、網羅した switch がコンパイルを止める。 */
  /* package */ static CommonProblem forConflict(final ConflictException.Kind kind) {
    return switch (kind) {
      case VERSION -> VERSION_CONFLICT;
      case LOCK -> LOCK_TIMEOUT;
      case UNIQUE -> UNIQUE_VIOLATION;
    };
  }
}
