package com.example.demo.ordering.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.integers;
import static org.quicktheories.generators.SourceDSL.longs;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 金額の不変条件を境界の両側で検証する。小数点以下の桁数は 0 から 6 桁を生成する。 */
@SuppressWarnings("PMD.TooManyStaticImports")
class MoneyTest {

  @Test
  @DisplayName("0 以上の金額は作れる")
  void acceptsAmountOfZeroOrMore() {
    qt().forAll(longs().between(0, Long.MAX_VALUE), integers().between(0, 6))
        .checkAssert((unscaled, scale) -> {
          final BigDecimal amount = BigDecimal.valueOf(unscaled, scale);
          assertThat(new Money(amount).amount()).isEqualByComparingTo(amount);
        });
  }

  @Test
  @DisplayName("0 未満の金額は、金額を含む IllegalArgumentException になる")
  void rejectsAmountBelowZero() {
    qt().forAll(longs().between(Long.MIN_VALUE, -1), integers().between(0, 6))
        .checkAssert((unscaled, scale) -> {
          final BigDecimal amount = BigDecimal.valueOf(unscaled, scale);
          assertThatThrownBy(() -> new Money(amount))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessage("amount must not be negative: amount=" + amount);
        });
  }
}
