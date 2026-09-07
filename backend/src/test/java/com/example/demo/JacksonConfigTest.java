package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * application.yaml の spring.jackson 設定が自動構成された JsonMapper に正しく適用されることを検証する。 DB/Redis/Web
 * を起動しない @JsonTest スライスで確認する。
 */
@JsonTest
class JacksonConfigTest {

  /** application.yaml の spring.jackson 設定が適用された、検証対象の {@code JsonMapper}。 */
  @Autowired private JsonMapper jsonMapper;

  @Test
  void instantsAreWrittenAsUtcIso8601Strings() {
    final Instant value = Instant.parse("2025-09-05T16:00:00Z");
    final String json = jsonMapper.writeValueAsString(new HasInstant(value));
    assertThat(json).isEqualTo("{\"when\":\"2025-09-05T16:00:00Z\"}");
  }

  @Test
  void durationsAreWrittenAsIso8601String() {
    final String json = jsonMapper.writeValueAsString(new HasDuration(Duration.ofHours(1)));
    assertThat(json).contains("PT1H");
  }

  @Test
  void nullPropertiesAreExcluded() {
    final String json = jsonMapper.writeValueAsString(new HasNullable("x", null));
    assertThat(json).contains("present").doesNotContain("missing");
  }

  @Test
  void unknownPropertiesAreIgnored() {
    // fail-on-unknown-properties: false なので例外にならない
    final HasName parsed = jsonMapper.readValue("{\"name\":\"a\",\"unknown\":1}", HasName.class);
    assertThat(parsed.name()).isEqualTo("a");
  }

  /* package */ record HasInstant(Instant when) {}

  /* package */ record HasDuration(Duration length) {}

  /* package */ record HasNullable(String present, String missing) {}

  /* package */ record HasName(String name) {}
}
