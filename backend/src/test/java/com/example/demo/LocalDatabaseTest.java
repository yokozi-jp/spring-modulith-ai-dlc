package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spring の外で DB へ書くプログラムが、ローカルかテストの環境だけを受け入れることを確かめる。 */
class LocalDatabaseTest {

  @Test
  @DisplayName("目印が local か test で DB_HOST が loopback のときだけ、ローカルの環境とみなす")
  // loopback の判定を確かめるテストのデータであり、接続先の設定ではない。
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

  private static Map<String, String> env(final String environment, final String host) {
    return Map.of("OTEL_DEPLOYMENT_ENVIRONMENT_NAME", environment, "DB_HOST", host);
  }
}
