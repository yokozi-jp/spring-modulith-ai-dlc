package com.example.demo.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 404 と 422 の業務上の失敗の例外が、原因を持てないことを確かめる（ADR-062）。 */
class BusinessFailureExceptionsTest {

  private static Stream<RuntimeException> exceptions() {
    return Stream.of(
        new NotFoundException("order not found: orderId=1"),
        new BusinessRuleViolationException("order is not placed: orderId=1"));
  }

  @ParameterizedTest
  @MethodSource("exceptions")
  @DisplayName("原因は null で、後から原因を付けられない")
  void rejectsInitCause(final RuntimeException exception) {
    assertThat(exception).hasNoCause();
    assertThatThrownBy(() -> exception.initCause(new IllegalStateException("select secret")))
        .isInstanceOf(IllegalStateException.class);
  }
}
