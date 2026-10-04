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

  /** lock_timeout の値を渡す環境変数。 */
  private static final String LOCK_TIMEOUT = "DB_LOCK_TIMEOUT_MS";

  /** statement_timeout の値を渡す環境変数。 */
  private static final String STATEMENT_TIMEOUT = "DB_STATEMENT_TIMEOUT_MS";

  /** idle_in_transaction_session_timeout の値を渡す環境変数。 */
  private static final String IDLE_IN_TRANSACTION_SESSION_TIMEOUT =
      "DB_IDLE_IN_TRANSACTION_TIMEOUT_MS";

  /** 値が満たすべき条件を検証する。 */
  DatabaseTimeLimits {
    requirePositive(LOCK_TIMEOUT, lockTimeoutMs);
    requirePositive(STATEMENT_TIMEOUT, statementTimeoutMs);
    requirePositive(IDLE_IN_TRANSACTION_SESSION_TIMEOUT, idleInTransactionSessionTimeoutMs);
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

  /** 環境変数 {@code DB_*_TIMEOUT_MS} を読み、検証した値を返す。 */
  /* default */ static DatabaseTimeLimits from(final PropertyResolver resolver) {
    return new DatabaseTimeLimits(
        parse(resolver, LOCK_TIMEOUT),
        parse(resolver, STATEMENT_TIMEOUT),
        parse(resolver, IDLE_IN_TRANSACTION_SESSION_TIMEOUT));
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

  private static int parse(final PropertyResolver resolver, final String name) {
    final String value = resolver.getProperty(name);
    if (value == null) {
      throw new IllegalArgumentException(name + " を設定する（ADR-055）");
    }
    if (!DIGITS.matcher(value).matches()) {
      throw invalid(name, value);
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      // PostgreSQL の三つの設定は int のため、int に収まらない値は最初の接続で失敗する。
      throw new IllegalArgumentException(invalid(name, value).getMessage(), e);
    }
  }

  private static void requirePositive(final String name, final int value) {
    if (value < MIN_MS) {
      throw invalid(name, String.valueOf(value));
    }
  }

  private static IllegalArgumentException invalid(final String name, final String value) {
    return new IllegalArgumentException(
        name
            + " は "
            + MIN_MS
            + " 以上 "
            + Integer.MAX_VALUE
            + " 以下のミリ秒の整数にする（ADR-055）: 値=["
            + value
            + "]");
  }
}
