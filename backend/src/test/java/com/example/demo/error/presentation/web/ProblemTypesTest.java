package com.example.demo.error.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;

import errorfixture.presentation.web.ErrorFixtureFailure;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 応答が返す problem type の一覧と、その title が日本語と英語の messages にあることを確かめる（ADR-125）。 */
class ProblemTypesTest {

  @Test
  @DisplayName("problem type の一覧は、決めた値と順のままである")
  void problemTypesArePinned() {
    // type はクライアントが分岐に使う契約である。理由の定数の名前を変える、enum を別のモジュールへ移す、理由を消すと、ここが落ちる。
    // 理由を足したら行を足す。テストのソースの enum が com.example.demo の下にあっても、ここに現れて落ちる。
    assertThat(ProblemTypes.all())
        .containsExactly(
            "about:blank",
            "/problems/validation-error",
            "/problems/csrf-token-invalid",
            "/problems/tenant-not-selected",
            "/problems/version-conflict",
            "/problems/lock-timeout",
            "/problems/unique-violation",
            "/problems/ordering/no-lines",
            "/problems/ordering/not-draft",
            "/problems/ordering/product-discontinued",
            "/problems/ordering/product-not-found",
            "/problems/tenancy/last-admin",
            "/problems/tenancy/not-tenant-member",
            "/problems/tenancy/tenant-already-selected");
  }

  @ParameterizedTest
  @ValueSource(strings = {"messages.properties", "messages_ja.properties"})
  @DisplayName("about:blank 以外のすべての problem type の title のキーが、英語と日本語の messages にある")
  void everyProblemTitleIsTranslated(final String bundle) throws IOException {
    final Properties messages = load(bundle);

    assertThat(ProblemTypes.paths().map(ProblemTypes::titleKey))
        .as(bundle + " にない title のキー")
        .filteredOn(key -> !messages.containsKey(key))
        .isEmpty();
  }

  @Test
  @DisplayName("走査は、定数の本体で抽象メソッドを実装する enum の定数も拾う")
  void scanFindsEnumsWithConstantBodies() {
    assertThat(ProblemTypes.reasonsUnder("errorfixture"))
        .containsExactly(ErrorFixtureFailure.REJECTED);
  }

  /** フォールバックを通さずに、1 つの properties のファイルだけを読む。 */
  private static Properties load(final String bundle) throws IOException {
    final Properties messages = new Properties();
    try (Reader reader =
        new InputStreamReader(
            Objects.requireNonNull(
                ProblemTypesTest.class.getResourceAsStream("/" + bundle), bundle),
            StandardCharsets.UTF_8)) {
      messages.load(reader);
    }
    return messages;
  }
}
