package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * application.yaml の spring.jackson 設定が自動構成された JsonMapper に正しく適用されることを検証する。
 * DB/Redis/Web を起動しない @JsonTest スライスで確認する。
 */
@JsonTest
class JacksonConfigTest {

  @Autowired private JsonMapper jsonMapper;

  @Test
  void datesAreWrittenAsIso8601String() {
    var value = OffsetDateTime.of(2025, 9, 5, 16, 0, 0, 0, ZoneOffset.UTC);
    String json = jsonMapper.writeValueAsString(new HasDate(value));
    // 数値配列 [2025,9,5,...] でなく ISO 文字列であること
    assertThat(json).contains("2025-09-05T16:00:00").doesNotContain("[2025");
  }

  @Test
  void durationsAreWrittenAsIso8601String() {
    String json = jsonMapper.writeValueAsString(new HasDuration(Duration.ofHours(1)));
    assertThat(json).contains("PT1H");
  }

  @Test
  void nullPropertiesAreExcluded() {
    String json = jsonMapper.writeValueAsString(new HasNullable("x", null));
    assertThat(json).contains("present").doesNotContain("missing");
  }

  @Test
  void unknownPropertiesAreIgnored() {
    // fail-on-unknown-properties: false なので例外にならない
    HasName parsed = jsonMapper.readValue("{\"name\":\"a\",\"unknown\":1}", HasName.class);
    assertThat(parsed.name()).isEqualTo("a");
  }

  record HasDate(OffsetDateTime when) {}

  record HasDuration(Duration length) {}

  record HasNullable(String present, String missing) {}

  record HasName(String name) {}
}
