package com.example.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 開発用の代表データのシーダーが、DB 接続より前に環境を検査することを確かめる。 */
class LocalDataSeederEntryPointTest {

  @Test
  @DisplayName("非 local/test の reset は DB 接続より前に拒む")
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  void rejectsNonLocalResetBeforeDatabaseConnection() {
    final Map<String, String> env =
        Map.of(
            "OTEL_DEPLOYMENT_ENVIRONMENT_NAME", "stg",
            "DB_HOST", "127.0.0.1",
            "DB_PORT", "1",
            "DB_NAME", "demo",
            "DB_USERNAME", "demo",
            "DB_PASSWORD", "demo");

    assertThatThrownBy(() -> LocalDataSeeder.run(new String[] {"reset"}, env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "ローカルかテストの環境でないため実行しません: " + "OTEL_DEPLOYMENT_ENVIRONMENT_NAME=stg, DB_HOST=127.0.0.1");
  }
}
