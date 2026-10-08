package com.example.demo.shared.failure;

import java.io.Serial;

/**
 * 業務規則の違反を表す。許されない状態遷移や、要求を今の状態へ適用できない場合に投げる。
 *
 * <p>HTTP では 422 Unprocessable Content になる。
 */
public final class BusinessRuleViolationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから業務規則の違反の例外を作る。原因は持たない。 */
  public BusinessRuleViolationException(final String message) {
    // 原因を持たせない。原因の例外のメッセージが SQL や入力値をログに出さないようにするため（ADR-062）。
    super(message, null);
  }
}
