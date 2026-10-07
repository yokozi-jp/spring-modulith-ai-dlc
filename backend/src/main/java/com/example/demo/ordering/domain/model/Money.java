package com.example.demo.ordering.domain.model;

import com.example.demo.shared.failure.BusinessRuleViolationException;
import java.math.BigDecimal;

/**
 * 円の金額。
 *
 * @param amount 0 以上の金額
 */
public record Money(BigDecimal amount) {

  /** 0 円。 */
  public static final Money ZERO = new Money(BigDecimal.ZERO);

  /** 金額が 0 以上であることを確かめる。 */
  public Money {
    if (amount.signum() < 0) {
      throw new BusinessRuleViolationException("amount must not be negative: amount=" + amount);
    }
  }

  /** 金額を足した値を返す。 */
  public Money plus(final Money other) {
    return new Money(amount.add(other.amount));
  }

  /** 数量を掛けた値を返す。 */
  public Money times(final Quantity quantity) {
    return new Money(amount.multiply(BigDecimal.valueOf(quantity.value())));
  }
}
