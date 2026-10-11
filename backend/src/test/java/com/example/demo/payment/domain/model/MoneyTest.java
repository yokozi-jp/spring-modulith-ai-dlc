package com.example.demo.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.failure.BusinessRuleViolationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 請求の金額が 0 以上に限られることを検証する。 */
class MoneyTest {

  @Test
  @DisplayName("0 円の金額は作れる")
  void zeroIsAllowed() {
    assertThat(new Money(BigDecimal.ZERO).amount()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("負の金額は、金額を含む BusinessRuleViolationException になる")
  void negativeIsRejected() {
    assertThatThrownBy(() -> new Money(new BigDecimal("-1")))
        .isInstanceOf(BusinessRuleViolationException.class)
        .hasMessage("amount must not be negative: amount=-1");
  }
}
