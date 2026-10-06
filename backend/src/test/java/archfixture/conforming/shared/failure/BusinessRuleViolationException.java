package archfixture.conforming.shared.failure;

import java.io.Serial;

/** 業務規則の違反を表す。 */
public final class BusinessRuleViolationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから業務規則の違反の例外を作る。 */
  public BusinessRuleViolationException(final String message) {
    super(message);
  }
}
