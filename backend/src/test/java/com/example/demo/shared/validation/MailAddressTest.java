package com.example.demo.shared.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code openapi/mail-address-cases.json} をフロントエンドの Zod の検査と共有し、2 つの検査が同じ形を通すことを確かめる。 */
class MailAddressTest {

  /** Bean Validation の検査器。 */
  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  /** バックエンドとフロントエンドが共有する入力。 */
  private static final JsonNode SHARED_CASES = readCases();

  /** 検査対象の本文。 */
  /* default */ record Body(@MailAddress String email) {}

  private static JsonNode readCases() {
    return JsonMapper.builder()
        .build()
        .readTree(Path.of("../openapi/mail-address-cases.json").toFile());
  }

  private static Stream<String> cases(final String key) {
    return SHARED_CASES.get(key).valueStream().map(JsonNode::asText);
  }

  /* default */ static Stream<String> accepted() {
    return cases("accept");
  }

  /* default */ static Stream<String> rejected() {
    return cases("reject");
  }

  @ParameterizedTest
  @MethodSource("accepted")
  @DisplayName("クライアントが通す形をサーバーも通す")
  void acceptsWhatTheClientAccepts(final String email) {
    assertThat(VALIDATOR.validate(new Body(email))).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("rejected")
  @DisplayName("クライアントが断る形をサーバーも断る")
  void rejectsWhatTheClientRejects(final String email) {
    assertThat(VALIDATOR.validate(new Body(email))).hasSize(1);
  }

  @Test
  @DisplayName("式が、フロントエンドの Zod の式と同じ文字列である")
  void patternEqualsTheZodPattern() {
    assertThat(MailAddress.PATTERN).isEqualTo(SHARED_CASES.get("pattern").asText());
  }

  @Test
  @DisplayName("OpenAPI には format: email が出る")
  void exposesEmailFormatInOpenApi() {
    final Schema<?> email =
        (Schema<?>)
            ModelConverters.getInstance(true)
                .read(Body.class)
                .get("Body")
                .getProperties()
                .get("email");

    assertThat(email.getFormat()).isEqualTo("email");
  }
}
