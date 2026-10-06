package com.example.demo.shared.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link ExpectedLockNo} が、INSERT の版（1）未満を受け付けないことを確かめる。 */
class ExpectedLockNoTest {

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
  @DisplayName("1 未満の版は、値を含むメッセージの IllegalArgumentException で拒否する")
  void rejectsValuesBelowOne(final long value) {
    assertThatThrownBy(() -> new ExpectedLockNo(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("value=" + value);
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, Long.MAX_VALUE})
  @DisplayName("1 以上の版は受け付け、value() が同じ値を返す")
  void acceptsValuesFromOne(final long value) {
    assertThat(new ExpectedLockNo(value).value()).isEqualTo(value);
  }

  @Test
  @DisplayName("同じ値の ExpectedLockNo は等しい")
  void equalValuesAreEqual() {
    assertThat(new ExpectedLockNo(3L)).isEqualTo(new ExpectedLockNo(3L));
    assertThat(new ExpectedLockNo(3L)).isNotEqualTo(new ExpectedLockNo(4L));
  }
}
