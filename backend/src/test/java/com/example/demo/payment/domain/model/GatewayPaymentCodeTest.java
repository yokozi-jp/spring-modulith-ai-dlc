package com.example.demo.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 決済の識別子の不変条件を境界の両側で検証する。 */
class GatewayPaymentCodeTest {

  /** 空白でない文字を 1 つ以上含み、64 文字以下の識別子。前後の空白は除かない。 */
  static Stream<String> acceptedValues() {
    return Stream.of("a", " a ", "a".repeat(64));
  }

  @ParameterizedTest
  @MethodSource("acceptedValues")
  @DisplayName("空白でない文字を含む 64 文字以下の識別子は、そのまま作れる")
  void acceptsNonBlankUpToMaxLength(final String value) {
    assertThat(new GatewayPaymentCode(value).value()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   ", "\t\n"})
  @DisplayName("空と空白だけの識別子は作れない")
  void rejectsBlank(final String value) {
    assertThatThrownBy(() -> new GatewayPaymentCode(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("gatewayPaymentCode must not be blank");
  }

  @Test
  @DisplayName("65 文字の識別子は作れない")
  void rejectsOneOverMaxLength() {
    assertThatThrownBy(() -> new GatewayPaymentCode("a".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("gatewayPaymentCode is too long: length=65");
  }
}
