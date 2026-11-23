package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.longs;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.json.JsonMapper;

/**
 * application.yaml の spring.jackson 設定が自動構成された JsonMapper に正しく適用されることを検証する。 DB/Web を起動しない @JsonTest
 * スライスで確認する。
 */
@JsonTest
class JacksonConfigTest {

  /** application.yaml の spring.jackson 設定が適用された、検証対象の {@code JsonMapper}。 */
  @Autowired private JsonMapper jsonMapper;

  @Test
  @DisplayName("Instant を UTC の ISO 8601 文字列として直列化する")
  void instantsAreWrittenAsUtcIso8601Strings() {
    final Instant value = Instant.parse("2025-09-05T16:00:00Z");
    final String json = jsonMapper.writeValueAsString(new HasInstant(value));
    assertThat(json).isEqualTo("{\"when\":\"2025-09-05T16:00:00Z\"}");
  }

  @Test
  @DisplayName("Instant はマイクロ秒精度で JSON 往復しても変化しない")
  void instantsRoundTripAtMicrosecondPrecision() {
    qt().withExamples(1_000)
        .forAll(longs().between(-2_208_988_800_000_000L, 4_133_980_800_000_000L))
        .checkAssert(epochMicros -> {
          final long microsPerSecond = TimeUnit.SECONDS.toMicros(1);
          final Instant expected =
              Instant.ofEpochSecond(
                  Math.floorDiv(epochMicros, microsPerSecond),
                  TimeUnit.MICROSECONDS.toNanos(Math.floorMod(epochMicros, microsPerSecond)));
          final String json = jsonMapper.writeValueAsString(new HasInstant(expected));
          final HasInstant actual = jsonMapper.readValue(json, HasInstant.class);

          assertThat(json)
              .as("InstantはUTCのRFC 3339文字列として直列化されること")
              .isEqualTo("{\"when\":\"" + expected + "\"}");
          assertThat(actual.when()).as("マイクロ秒精度のInstantがJSON往復で変化しないこと").isEqualTo(expected);
        });
  }

  @Test
  @DisplayName("LocalDate を full-date の文字列として読み書きする")
  void localDatesAreWrittenAndReadAsFullDate() {
    final String json = jsonMapper.writeValueAsString(new HasDate(LocalDate.parse("2023-09-30")));
    assertThat(json).isEqualTo("{\"day\":\"2023-09-30\"}");
    assertThat(jsonMapper.readValue(json, HasDate.class).day())
        .isEqualTo(LocalDate.parse("2023-09-30"));
  }

  @Test
  @DisplayName("LocalDate は時刻つきの文字列を受けない")
  void localDatesRejectTimestamps() {
    // 既定の寛容な読み取りだと 2023-09-29 として受け、東京の 9 月 30 日がずれる。
    assertThatThrownBy(
            () -> jsonMapper.readValue("{\"day\":\"2023-09-29T15:00:00Z\"}", HasDate.class))
        .isInstanceOf(InvalidFormatException.class);
  }

  @Test
  @DisplayName("Instant と LocalDate は空文字列を null にせず拒否する")
  void emptyStringsAreRejectedForInstantAndLocalDate() {
    assertThatThrownBy(() -> jsonMapper.readValue("{\"when\":\"\"}", HasInstant.class))
        .isInstanceOf(JacksonException.class);
    assertThatThrownBy(() -> jsonMapper.readValue("{\"day\":\"\"}", HasDate.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  @DisplayName("Duration を ISO 8601 文字列として直列化する")
  void durationsAreWrittenAsIso8601String() {
    final String json = jsonMapper.writeValueAsString(new HasDuration(Duration.ofHours(1)));
    assertThat(json).contains("PT1H");
  }

  @Test
  @DisplayName("null プロパティを JSON 出力から除外する")
  void nullPropertiesAreExcluded() {
    final String json = jsonMapper.writeValueAsString(new HasNullable("x", null));
    assertThat(json).contains("present").doesNotContain("missing");
  }

  @Test
  @DisplayName("未知のプロパティを無視して読み込む")
  void unknownPropertiesAreIgnored() {
    // fail-on-unknown-properties: false なので例外にならない
    final HasName parsed = jsonMapper.readValue("{\"name\":\"a\",\"unknown\":1}", HasName.class);
    assertThat(parsed.name()).isEqualTo("a");
  }

  /* package */ record HasInstant(Instant when) {}

  /* package */ record HasDate(LocalDate day) {}

  /* package */ record HasDuration(Duration length) {}

  /* package */ record HasNullable(String present, String missing) {}

  /* package */ record HasName(String name) {}
}
