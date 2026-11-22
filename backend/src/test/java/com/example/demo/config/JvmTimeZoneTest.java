package com.example.demo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * テストの JVM が、本番とも業務とも違い夏時間のあるタイムゾーンで動くことを検証する（ADR-109）。
 *
 * <p>{@code test} タスクと、JVM を分けて起こす PIT の {@code jvmArgs} の設定の外れを検出する。
 */
class JvmTimeZoneTest {

  @Test
  @DisplayName("テスト JVM の既定のタイムゾーンは America/New_York である")
  void defaultTimeZoneIsNewYork() {
    assertEquals(
        ZoneId.of("America/New_York"), ZoneId.systemDefault(), "既定のタイムゾーンが America/New_York であること");
  }
}
