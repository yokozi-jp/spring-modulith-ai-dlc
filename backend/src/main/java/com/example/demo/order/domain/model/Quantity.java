package com.example.demo.order.domain.model;

import com.example.demo.shared.failure.BusinessRuleViolationException;

/**
 * 明細の数量。
 *
 * @param value 1 以上の数量
 */
public record Quantity(int value) {

  /** 数量の最小値。 */
  private static final int MINIMUM = 1;

  /** 数量が 1 以上であることを確かめる。 */
  public Quantity {
    if (value < MINIMUM) {
      throw new BusinessRuleViolationException("quantity must be 1 or greater: value=" + value);
    }
  }
}
