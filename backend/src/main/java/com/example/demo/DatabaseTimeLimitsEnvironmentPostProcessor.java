package com.example.demo;

import com.zaxxer.hikari.HikariConfig;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * DB セッションの時間の上限を、Bean を作る前に検証する（ADR-055）。
 *
 * <p>application.yaml を読み込んだ後に動き、条件を満たさない値ではアプリケーションを起動しない。 遅延初期化やテストスライスでも、検証していない値で接続を作らない。
 */
final class DatabaseTimeLimitsEnvironmentPostProcessor implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    DatabaseTimeLimits.from(environment)
        .requireStatementTimeoutAtMost(effectiveConnectionTimeoutMs(environment));
  }

  /** 未設定なら HikariCP の既定値、0 なら無制限という HikariCP の解釈で実効値を求める。 */
  private static long effectiveConnectionTimeoutMs(final ConfigurableEnvironment environment) {
    final HikariConfig hikari = new HikariConfig();
    final Long configured =
        environment.getProperty("spring.datasource.hikari.connection-timeout", Long.class);
    if (configured != null) {
      hikari.setConnectionTimeout(configured);
    }
    return hikari.getConnectionTimeout();
  }
}
