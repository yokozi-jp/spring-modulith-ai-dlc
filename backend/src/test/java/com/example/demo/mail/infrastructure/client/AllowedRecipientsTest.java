package com.example.demo.mail.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.mail.domain.model.MailRecipient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 送ってよい宛先の、設定の読み方と、宛先との照合を検証する。 */
class AllowedRecipientsTest {

  @ParameterizedTest(name = "{0} に {1} は {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "*                                | anyone@example.org        | true",
        "@example.com                     | a@example.com             | true",
        "@example.com                     | A@EXAMPLE.com             | true",
        "@example.com                     | a@sub.example.com         | false",
        "@example.com                     | a@notexample.com          | false",
        "a@example.com                    | a@example.com             | true",
        "a@example.com                    | A@Example.COM             | true",
        "a@example.com                    | b@example.com             | false",
        "a@example.com                    | aa@example.com            | false",
        "a@example.com, @example.net      | x@example.net             | true",
        "' @example.com , a@example.org ' | a@example.org             | true",
        "a+tag@example.com                | a@example.com             | false",
      })
  @DisplayName("宛先は、* と、@ドメインと、アドレスの完全一致のどれかに当たるときだけ許す。大文字と小文字は区別せず、エイリアスは正規化しない")
  void matchesEntries(final String setting, final String recipient, final boolean expected) {
    assertThat(AllowedRecipients.parse(setting).allows(new MailRecipient(recipient)))
        .isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        ",",
        "example.com",
        "@com",
        "@.example.com",
        "*@example.com",
        "a@@example.com"
      })
  @DisplayName("項目がないか、* でも @ドメインでもアドレスでもない項目があれば、起動の時点で断る。メッセージに値を入れない")
  void invalidSettingIsRejected(final String setting) {
    assertThatThrownBy(() -> AllowedRecipients.parse(setting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mail-sender.allowed-recipients")
        .hasMessageNotContaining(setting.isBlank() ? "\u0000" : setting);
  }
}
