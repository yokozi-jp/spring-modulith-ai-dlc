package com.example.demo;

import java.util.regex.Pattern;
import org.springframework.core.env.PropertyResolver;

/**
 * DB セッションの時間の上限（ミリ秒）。
 *
 * <p>connection-init-sql の SET 文へ埋め込む値と、値どうしの関係を検証する（ADR-055）。
 *
 * @param lockTimeoutMs lock_timeout
 * @param statementTimeoutMs statement_timeout
 * @param idleInTransactionSessionTimeoutMs idle_in_transaction_session_timeout
 */
record DatabaseTimeLimits(
    int lockTimeoutMs, int statementTimeoutMs, int idleInTransactionSessionTimeoutMs) {

  /** SET 文へそのまま埋め込める、符号、単位、空白のない十進の数字列。 */
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");

  /** PostgreSQL では 0 が無効を意味するため、上限として働く最小の値。 */
  private static final int MIN_MS = 1;

  /** 値が満たすべき条件を検証する。 */
  DatabaseTimeLimits {
    requirePositive(Setting.LOCK_TIMEOUT, lockTimeoutMs);
    requirePositive(Setting.STATEMENT_TIMEOUT, statementTimeoutMs);
    requirePositive(Setting.IDLE_IN_TRANSACTION_SESSION_TIMEOUT, idleInTransactionSessionTimeoutMs);
    // 同じか長いと、ロック待ちは 55P03 の競合でなく 57014 の取り消しで終わる。
    if (lockTimeoutMs >= statementTimeoutMs) {
      throw new IllegalArgumentException(
          "DB_LOCK_TIMEOUT_MS は DB_STATEMENT_TIMEOUT_MS より短くする（ADR-055）: DB_LOCK_TIMEOUT_MS=["
              + lockTimeoutMs
              + "], DB_STATEMENT_TIMEOUT_MS=["
              + statementTimeoutMs
              + "]");
    }
  }

  /** 設定の {@code app.database.time-limits.*} を読み、検証した値を返す。 */
  /* default */ static DatabaseTimeLimits from(final PropertyResolver resolver) {
    return new DatabaseTimeLimits(
        parse(resolver, Setting.LOCK_TIMEOUT),
        parse(resolver, Setting.STATEMENT_TIMEOUT),
        parse(resolver, Setting.IDLE_IN_TRANSACTION_SESSION_TIMEOUT));
  }

  /**
   * statement_timeout が接続取得の待ち時間を超えないことを検証する。
   *
   * @param connectionTimeoutMs HikariCP の connection-timeout の実効値
   */
  /* default */ void requireStatementTimeoutAtMost(final long connectionTimeoutMs) {
    if (this.statementTimeoutMs > connectionTimeoutMs) {
      throw new IllegalArgumentException(
          "DB_STATEMENT_TIMEOUT_MS は spring.datasource.hikari.connection-timeout"
              + "（DB_POOL_CONNECTION_TIMEOUT_MS）以下にする（ADR-055）: DB_STATEMENT_TIMEOUT_MS=["
              + this.statementTimeoutMs
              + "], connection-timeout の実効値=["
              + connectionTimeoutMs
              + "]");
    }
  }

  private static int parse(final PropertyResolver resolver, final Setting setting) {
    final String value = resolver.getProperty(setting.property);
    if (value == null) {
      throw new IllegalArgumentException(setting + "を設定する（ADR-055）");
    }
    if (!DIGITS.matcher(value).matches()) {
      throw invalid(setting, value);
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      // PostgreSQL の三つの設定は int のため、int に収まらない値は最初の接続で失敗する。
      throw new IllegalArgumentException(invalid(setting, value).getMessage(), e);
    }
  }

  private static void requirePositive(final Setting setting, final int value) {
    if (value < MIN_MS) {
      throw invalid(setting, String.valueOf(value));
    }
  }

  private static IllegalArgumentException invalid(final Setting setting, final String value) {
    return new IllegalArgumentException(setting + "は 1 以上のミリ秒の整数にする（ADR-055）: 値=[" + value + "]");
  }

  /** 検証する設定の環境変数とプロパティ。 */
  private enum Setting {
    LOCK_TIMEOUT("DB_LOCK_TIMEOUT_MS", "app.database.time-limits.lock-timeout-ms"),
    STATEMENT_TIMEOUT("DB_STATEMENT_TIMEOUT_MS", "app.database.time-limits.statement-timeout-ms"),
    IDLE_IN_TRANSACTION_SESSION_TIMEOUT(
        "DB_IDLE_IN_TRANSACTION_TIMEOUT_MS",
        "app.database.time-limits.idle-in-transaction-session-timeout-ms");

    /** 値を渡す環境変数の名前。 */
    private final String environmentVariable;

    /** application.yaml のプロパティ名。 */
    private final String property;

    Setting(final String environmentVariable, final String property) {
      this.environmentVariable = environmentVariable;
      this.property = property;
    }

    @Override
    public String toString() {
      return this.environmentVariable + "（" + this.property + "）";
    }
  }
}
