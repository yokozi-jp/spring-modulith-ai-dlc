package com.example.demo.mail.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quicktheories.QuickTheory.qt;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quicktheories.core.Gen;
import org.quicktheories.generators.SourceDSL;

/** メールの宛先、件名、本文、識別子の値オブジェクトの不変条件を検証する。 */
class MailValuesTest {

  /** 空白だけの文字列。空文字も含む。 */
  private static final Gen<String> BLANKS =
      SourceDSL.integers().between(0, 8).map(length -> " \t\n".repeat(length).substring(0, length));

  /** 空白でない文字を 1 つ以上含む、改行のない文字列。 */
  private static final Gen<String> SINGLE_LINES =
      SourceDSL.strings()
          .basicLatinAlphabet()
          .ofLengthBetween(0, 20)
          .map(text -> "x" + text.replaceAll("[\\r\\n]", ""));

  @Test
  @DisplayName("宛先、件名、本文、識別子は、空白だけの値では作れない")
  void rejectsBlankValues() {
    assertRejectsBlanks("MailRecipient", MailRecipient::new);
    assertRejectsBlanks("MailSubject", MailSubject::new);
    assertRejectsBlanks("MailBody", MailBody::new);
    assertRejectsBlanks("MailMessageId", MailMessageId::new);
  }

  @Test
  @DisplayName("改行のない、空白だけでない件名は作れる")
  void acceptsSingleLineSubjects() {
    qt().forAll(SINGLE_LINES)
        .checkAssert(value -> assertThat(new MailSubject(value).value()).as("件名").isEqualTo(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"招待\nBcc: x@example.com", "招待\r", "\r\n招待"})
  @DisplayName("改行を含む件名は、ヘッダーを足せないよう作れない")
  void rejectsSubjectsWithLineBreaks(final String value) {
    assertThatThrownBy(() -> new MailSubject(value))
        .as("value=%s", value)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line break");
  }

  @Test
  @DisplayName("本文は改行を含んでよい")
  void acceptsMultiLineBodies() {
    assertThat(new MailBody("1 行目\n2 行目").value()).as("本文").isEqualTo("1 行目\n2 行目");
  }

  @Test
  @DisplayName("宛先、件名、本文は、ログに渡されても toString で値を出さない")
  void hidesValuesFromToString() {
    assertThat(
            List.of(
                new MailRecipient("invitee-7f3a@example.net"),
                new MailSubject("山田様 招待のお知らせ"),
                new MailBody("https://app.example.com/invitations/token-7f3a")))
        .extracting(Object::toString)
        .as("toString")
        .containsExactly(
            "MailRecipient[value=***]", "MailSubject[value=***]", "MailBody[value=***]");
  }

  /** 空白だけの値で作ると {@link IllegalArgumentException} になることを、値の範囲全体で確かめる。 */
  private static void assertRejectsBlanks(final String type, final Function<String, ?> factory) {
    qt().forAll(BLANKS)
        .checkAssert(value ->
            assertThatThrownBy(() -> factory.apply(value))
                .as("%s(\"%s\")", type, value)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank"));
  }
}
