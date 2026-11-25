package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spring の外で DB へ書くプログラムが、ローカルかテストの環境だけを受け入れることを確かめる。 */
class LocalDatabaseTest {

  @Test
  @DisplayName("目印が local か test で DB_HOST が loopback のときだけ、ローカルの環境とみなす")
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  void acceptsOnlyLocalEnvironment() {
    assertThat(LocalDatabase.isLocalEnvironment(env("local", "localhost"))).isTrue();
    assertThat(LocalDatabase.isLocalEnvironment(env("test", "127.0.0.1"))).isTrue();
    for (final Map<String, String> rejected :
        List.of(
            env("stg", "localhost"),
            env("local", "db.example.com"),
            Map.of("DB_HOST", "localhost"))) {
      assertThat(LocalDatabase.isLocalEnvironment(rejected)).isFalse();
      assertThatThrownBy(() -> LocalDatabase.requireLocalEnvironment(rejected))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  @DisplayName("接続先は DB_HOST と DB_PORT から決め、DB_URL を読まない")
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  void ignoresDbUrl() {
    final Map<String, String> env =
        Map.of(
            "OTEL_DEPLOYMENT_ENVIRONMENT_NAME", "test",
            "DB_HOST", "127.0.0.1",
            "DB_PORT", "1",
            "DB_NAME", "demo",
            "DB_USERNAME", "u",
            "DB_PASSWORD", "p",
            "DB_URL", "jdbc:postgresql://db.example.com:5432/demo");

    assertThatThrownBy(() -> LocalDatabase.connect(env))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("127.0.0.1:1");
  }

  private static Map<String, String> env(final String environment, final String host) {
    return Map.of("OTEL_DEPLOYMENT_ENVIRONMENT_NAME", environment, "DB_HOST", host);
  }
}
