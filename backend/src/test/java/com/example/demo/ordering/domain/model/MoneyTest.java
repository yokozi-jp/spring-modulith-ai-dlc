package com.example.demo.ordering.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.longs;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 金額の不変条件を境界の両側で検証する。 */
class MoneyTest {

  @Test
  @DisplayName("0 以上の金額は作れる")
  void acceptsAmountOfZeroOrMore() {
    qt().forAll(longs().between(0, Long.MAX_VALUE))
        .checkAssert(
            value ->
                assertThat(new Money(BigDecimal.valueOf(value)).amount())
                    .isEqualByComparingTo(BigDecimal.valueOf(value)));
  }

  @Test
  @DisplayName("0 未満の金額は作れない")
  void rejectsAmountBelowZero() {
    qt().forAll(longs().between(Long.MIN_VALUE, -1))
        .checkAssert(
            value ->
                assertThatThrownBy(() -> new Money(BigDecimal.valueOf(value)))
                    .as("value=%d", value)
                    .isInstanceOf(IllegalArgumentException.class));
  }

  @Test
  @DisplayName("0 のすぐ外の負の小数は作れない")
  void rejectsSmallestNegativeFraction() {
    assertThatThrownBy(() -> new Money(new BigDecimal("-0.01")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
