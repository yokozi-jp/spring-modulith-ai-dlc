package com.example.demo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 開発用の代表データのシーダーの公開の入口が、DB 接続より前に環境を検査することを確かめる。
 *
 * <p>{@link LocalDataSeeder#main} は {@code run(args, System.getenv())} を呼ぶだけなので、{@code run}
 * を公開の入口として呼ぶ。DB_PORT=1 にし、接続を試みれば {@code SQLException} になって期待の {@code IllegalStateException}
 * と区別できるようにする。
 */
class LocalDataSeederEntryPointTest {

  @ParameterizedTest(name = "{0} を env={1}、DB_HOST={2} で拒む")
  @DisplayName("非 local/test か loopback でない接続先の seed と reset は、DB 接続より前に拒む")
  @CsvSource({"reset, stg, 127.0.0.1", "reset, test, db.stg.example", "seed, prod, localhost"})
  void rejectsNonLocalBeforeDatabaseConnection(
      final String mode, final String environment, final String host) {
    final Map<String, String> env =
        Map.of(
            "OTEL_DEPLOYMENT_ENVIRONMENT_NAME", environment,
            "DB_HOST", host,
            "DB_PORT", "1",
            "DB_NAME", "demo",
            "DB_USERNAME", "demo",
            "DB_PASSWORD", "demo");

    assertThatThrownBy(() -> LocalDataSeeder.run(new String[] {mode}, env))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(
            "ローカルかテストの環境でないため実行しません: OTEL_DEPLOYMENT_ENVIRONMENT_NAME="
                + environment
                + ", DB_HOST="
                + host);
  }
}
