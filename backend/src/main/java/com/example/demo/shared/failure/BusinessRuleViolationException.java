package com.example.demo.shared.failure;

import java.io.Serial;

/**
 * 業務規則の違反を表す。許されない状態遷移や、要求を今の状態へ適用できない場合に投げる。
 *
 * <p>HTTP では 422 Unprocessable Content になる。
 */
public final class BusinessRuleViolationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから業務規則の違反の例外を作る。 */
  public BusinessRuleViolationException(final String message) {
    super(message);
  }
}
