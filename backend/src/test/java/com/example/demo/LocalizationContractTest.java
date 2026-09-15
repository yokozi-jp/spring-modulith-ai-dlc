package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.demo.testkit.SharedTestConfiguration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;

/** Bean Validation が API と同じ MessageSource から locale 別の文言を解決することを検証する。 */
@SpringBootTest
@Import(SharedTestConfiguration.class)
class LocalizationContractTest {

  /** アプリケーションが自動構成する Bean Validation の入口。 */
  @Autowired private Validator validator;

  @Test
  @DisplayName("日本語 locale では検証メッセージを日本語で返す")
  void validationMessageUsesJapaneseLocale() {
    assertRequiredMessage(Locale.JAPANESE, "この項目は必須です。");
  }

  @Test
  @DisplayName("英語 locale では検証メッセージを英語で返す")
  void validationMessageUsesEnglishLocale() {
    assertRequiredMessage(Locale.ENGLISH, "This field is required.");
  }

  private void assertRequiredMessage(final Locale locale, final String expected) {
    LocaleContextHolder.setLocale(locale);
    try {
      final Set<ConstraintViolation<RequiredCommand>> violations =
          validator.validate(new RequiredCommand(""));
      assertEquals(1, violations.size(), "name が空のときは制約違反が一件であること");
      final ConstraintViolation<RequiredCommand> violation =
          violations.stream()
              .findFirst()
              .orElseThrow(() -> new AssertionError("name の制約違反が取得できること"));
      assertEquals(expected, violation.getMessage(), "locale=" + locale.toLanguageTag());
    } finally {
      LocaleContextHolder.resetLocaleContext();
    }
  }

  /** MessageSource の key を使う検証対象。 */
  private record RequiredCommand(@NotBlank(message = "{validation.required}") String name) {}
}
