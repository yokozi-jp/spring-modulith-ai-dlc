package archfixture.violating.ordering.domain.model;

import archfixture.conforming.shared.failure.BusinessRuleViolationException;

/**
 * 違反：valueObjectsDoNotThrowBusinessRuleViolation（値オブジェクトが 422 の例外を投げる）。
 *
 * @param percent 割引率
 */
public record Discount(int percent) {

  /** 割引率が 0 以上であることを確かめる。 */
  public Discount {
    if (percent < 0) {
      throw new BusinessRuleViolationException("percent must not be negative: percent=" + percent);
    }
  }
}
