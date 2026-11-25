package archfixture.conforming.shared.failure;

import java.io.Serial;

/** 業務規則の違反を表す。 */
public final class BusinessRuleViolationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * 理由の enum の定数とメッセージから例外を作る。
   *
   * @param <E> 理由の enum
   */
  public <E extends Enum<E> & FailureReason> BusinessRuleViolationException(
      final E reason, final String message) {
    super(reason.name() + ": " + message);
  }
}
