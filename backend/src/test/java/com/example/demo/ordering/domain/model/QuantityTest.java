package com.example.demo.ordering.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.integers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 数量の不変条件を境界の両側で検証する。 */
class QuantityTest {

  @Test
  @DisplayName("1 以上の数量は作れる")
  void acceptsQuantityOfOneOrMore() {
    qt().forAll(integers().between(1, Integer.MAX_VALUE))
        .checkAssert(value -> assertThat(new Quantity(value).value()).isEqualTo(value));
  }

  @Test
  @DisplayName("1 未満の数量は、数量を含む IllegalArgumentException になる")
  void rejectsQuantityBelowOne() {
    qt().forAll(integers().between(Integer.MIN_VALUE, 0))
        .checkAssert(value ->
            assertThatThrownBy(() -> new Quantity(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity must be 1 or greater: value=" + value));
  }
}
