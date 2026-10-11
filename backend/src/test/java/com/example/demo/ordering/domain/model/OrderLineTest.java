package com.example.demo.ordering.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 明細の同一性が明細の番号だけで決まることを検証する。 */
class OrderLineTest {

  /** 単価 100 円。 */
  private static final Money PRICE = new Money(new BigDecimal("100"));

  @Test
  @DisplayName("明細の番号が同じなら、商品と数量が違っても同じ明細として扱う")
  void sameLineNumberIsEqual() {
    final OrderLine line = line(1, 2);
    final OrderLine other = line(1, 5);

    assertThat(line).isEqualTo(other).hasSameHashCodeAs(other);
  }

  @Test
  @DisplayName("明細の番号が違う明細や、明細でない値とは等しくない")
  void differentLineNumberOrTypeIsNotEqual() {
    final OrderLine line = line(1, 2);

    assertThat(line).isNotEqualTo(line(2, 2)).isNotEqualTo("line-1");
  }

  /** 番号と数量を指定し、商品を毎回変えた明細を作る。 */
  private static OrderLine line(final int lineNumber, final int quantity) {
    return new OrderLine(
        lineNumber, new ProductId(UUID.randomUUID()), new Quantity(quantity), PRICE);
  }
}
