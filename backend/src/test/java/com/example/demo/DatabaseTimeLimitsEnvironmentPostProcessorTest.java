package com.example.demo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** 起動時に DB セッションの時間の上限を、HikariCP の接続取得の待ち時間と合わせて検証することを確かめる。 */
class DatabaseTimeLimitsEnvironmentPostProcessorTest {

  /** 検証する対象。 */
  private final DatabaseTimeLimitsEnvironmentPostProcessor postProcessor =
      new DatabaseTimeLimitsEnvironmentPostProcessor();

  private static MockEnvironment environment(final int statementTimeoutMs) {
    return new MockEnvironment()
        .withProperty("app.database.time-limits.lock-timeout-ms", "1")
        .withProperty(
            "app.database.time-limits.statement-timeout-ms", String.valueOf(statementTimeoutMs))
        .withProperty("app.database.time-limits.idle-in-transaction-session-timeout-ms", "10000");
  }

  private void postProcess(final MockEnvironment environment) {
    this.postProcessor.postProcessEnvironment(environment, new SpringApplication());
  }

  @Test
  @DisplayName("statement_timeout が connection-timeout 以下なら受け付ける")
  void acceptsStatementTimeoutWithinConnectionTimeout() {
    final MockEnvironment environment =
        environment(5000).withProperty("spring.datasource.hikari.connection-timeout", "5000");

    assertThatCode(() -> postProcess(environment)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("statement_timeout が connection-timeout より長ければ失敗する")
  void rejectsStatementTimeoutLongerThanConnectionTimeout() {
    final MockEnvironment environment =
        environment(6000).withProperty("spring.datasource.hikari.connection-timeout", "5000");

    assertThatThrownBy(() -> postProcess(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DB_STATEMENT_TIMEOUT_MS")
        .hasMessageContaining("connection-timeout");
  }

  @Test
  @DisplayName("connection-timeout が未設定なら HikariCP の既定値と比べる")
  void comparesWithHikariDefaultWhenConnectionTimeoutIsUnset() {
    final int hikariDefault = Math.toIntExact(new HikariConfig().getConnectionTimeout());

    assertThatCode(() -> postProcess(environment(hikariDefault)))
        .as("statement_timeout=%s", hikariDefault)
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> postProcess(environment(hikariDefault + 1)))
        .as("statement_timeout=%s", hikariDefault + 1)
        .hasMessageContaining("connection-timeout");
  }

  @Test
  @DisplayName("connection-timeout が 0 なら HikariCP は無制限に待つため、statement_timeout を制限しない")
  void acceptsAnyStatementTimeoutWhenConnectionTimeoutIsUnlimited() {
    final MockEnvironment environment =
        environment(Integer.MAX_VALUE)
            .withProperty("spring.datasource.hikari.connection-timeout", "0");

    assertThatCode(() -> postProcess(environment)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("条件を満たさない環境変数では、Bean を作る前にアプリケーションの起動に失敗する")
  void applicationFailsToStartWithInvalidEnvironmentVariable() {
    final SpringApplication application = new SpringApplication(DemoApplication.class);

    assertThatThrownBy(
            () ->
                application.run(
                    "--DB_LOCK_TIMEOUT_MS=0",
                    "--DB_STATEMENT_TIMEOUT_MS=5000",
                    "--DB_IDLE_IN_TRANSACTION_TIMEOUT_MS=10000",
                    "--DB_POOL_CONNECTION_TIMEOUT_MS=5000"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DB_LOCK_TIMEOUT_MS")
        .hasMessageContaining("1 以上");
  }
}
