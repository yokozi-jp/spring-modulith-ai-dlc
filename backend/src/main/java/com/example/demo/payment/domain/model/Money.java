package com.example.demo.payment.domain.model;

import java.math.BigDecimal;

/**
 * 円の金額。
 *
 * @param amount 0 以上の金額
 */
public record Money(BigDecimal amount) {

  /** 金額が 0 以上であることを確かめる。 */
  public Money {
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("amount must not be negative: amount=" + amount);
    }
  }
}
