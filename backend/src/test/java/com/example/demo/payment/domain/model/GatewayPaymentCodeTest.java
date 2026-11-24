package com.example.demo.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 決済の識別子の不変条件を境界の両側で検証する。 */
class GatewayPaymentCodeTest {

  @Test
  @DisplayName("64 文字の識別子は作れる")
  void acceptsMaxLength() {
    final String value = "a".repeat(64);

    assertThat(new GatewayPaymentCode(value).value()).isEqualTo(value);
  }

  @Test
  @DisplayName("1 文字の識別子は作れる")
  void acceptsSingleCharacter() {
    assertThat(new GatewayPaymentCode("a").value()).isEqualTo("a");
  }

  @Test
  @DisplayName("前後に空白がある識別子は、そのまま作れる")
  void acceptsSurroundingWhitespace() {
    assertThat(new GatewayPaymentCode(" a ").value()).isEqualTo(" a ");
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
