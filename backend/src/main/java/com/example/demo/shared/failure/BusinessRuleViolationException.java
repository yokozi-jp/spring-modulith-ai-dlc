package com.example.demo.shared.failure;

import java.io.Serial;

/**
 * 業務規則の違反を表す。許されない状態遷移や、要求を今の状態へ適用できない場合に投げる。
 *
 * <p>HTTP では 422 Unprocessable Content になり、problem type と title は {@link FailureReason} から決まる。
 * メッセージは応答に入れず、ログにだけ出す。
 */
public final class BusinessRuleViolationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** 違反の理由。 */
  private final FailureReason failureReason;

  /**
   * 理由とログ用のメッセージから業務規則の違反の例外を作る。原因は持たない。
   *
   * <p>理由を enum の定数に限る。type は定数の名前から作るため、enum でない実装は名前を保てない（ADR-125）。
   *
   * @param <E> 理由の enum
   */
  public <E extends Enum<E> & FailureReason> BusinessRuleViolationException(
      final E reason, final String message) {
    // 原因を持たせない。原因の例外のメッセージが SQL や入力値をログに出さないようにするため（ADR-062）。
    super(message, null);
    this.failureReason = reason;
  }

  /** 違反の理由を返す。 */
  public FailureReason reason() {
    return failureReason;
  }
}
