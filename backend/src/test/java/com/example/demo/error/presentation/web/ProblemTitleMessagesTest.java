package com.example.demo.error.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.failure.FailureReason;
import com.example.demo.testkit.FailureReasons;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 422 の理由と 409 の種類のすべてに、日本語と英語の problem の title があることを確かめる（ADR-125）。 */
class ProblemTitleMessagesTest {

  @Test
  @DisplayName("FailureReason を実装する型は 1 つ以上あり、すべて enum である")
  void failureReasonsAreEnums() {
    assertThat(FailureReasons.implementations())
        .isNotEmpty()
        .allSatisfy(type -> assertThat(type.isEnum()).as(type.getName()).isTrue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"messages.properties", "messages_ja.properties"})
  @DisplayName("すべての理由と競合の種類の title のキーが、英語と日本語の messages にある")
  void everyProblemTitleIsTranslated(final String bundle) throws IOException {
    final Properties messages = load(bundle);

    assertThat(titleKeys())
        .as(bundle + " にない title のキー")
        .filteredOn(key -> !messages.containsKey(key))
        .isEmpty();
  }

  @Test
  @DisplayName("理由の type は、モジュールと enum をまたいで重複しない")
  void failureTypesAreUnique() {
    assertThat(FailureReasons.constants()).extracting(FailureReason::type).doesNotHaveDuplicates();
  }

  /** 理由と競合の種類から作る title のキー。 */
  private static List<String> titleKeys() {
    return Stream.concat(
            FailureReasons.constants().stream().map(FailureReason::titleKey),
            Arrays.stream(ConflictException.Kind.values())
                .map(kind -> "problem.title." + ApiProblemDetails.conflictProblemName(kind)))
        .toList();
  }

  /** フォールバックを通さずに、1 つの properties のファイルだけを読む。 */
  private static Properties load(final String bundle) throws IOException {
    final Properties messages = new Properties();
    try (Reader reader =
        new InputStreamReader(
            Objects.requireNonNull(
                ProblemTitleMessagesTest.class.getResourceAsStream("/" + bundle), bundle),
            StandardCharsets.UTF_8)) {
      messages.load(reader);
    }
    return messages;
  }
}
