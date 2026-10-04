package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

/** DB セッションの時間の上限の値と、値どうしの関係の検証を確かめる。 */
class DatabaseTimeLimitsTest {

  /** lock_timeout のプロパティ名。 */
  private static final String LOCK = "app.database.time-limits.lock-timeout-ms";

  /** statement_timeout のプロパティ名。 */
  private static final String STATEMENT = "app.database.time-limits.statement-timeout-ms";

  /** idle_in_transaction_session_timeout のプロパティ名。 */
  private static final String IDLE =
      "app.database.time-limits.idle-in-transaction-session-timeout-ms";

  private static MockEnvironment validEnvironment() {
    return new MockEnvironment()
        .withProperty(LOCK, "1000")
        .withProperty(STATEMENT, "5000")
        .withProperty(IDLE, "10000");
  }

  @Test
  @DisplayName("条件を満たす値はそのまま読む")
  void readsValidValues() {
    assertThat(DatabaseTimeLimits.from(validEnvironment()))
        .isEqualTo(new DatabaseTimeLimits(1000, 5000, 10_000));
  }

  @ParameterizedTest(name = "{0}=[{1}]")
  @DisplayName("1 以上のミリ秒の整数でない値は、環境変数の名前を示して失敗する")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        LOCK + "|0|DB_LOCK_TIMEOUT_MS",
        LOCK + "|-1|DB_LOCK_TIMEOUT_MS",
        LOCK + "|abc|DB_LOCK_TIMEOUT_MS",
        LOCK + "|1s|DB_LOCK_TIMEOUT_MS",
        LOCK + "|\"\"|DB_LOCK_TIMEOUT_MS",
        LOCK + "|\" 1000\"|DB_LOCK_TIMEOUT_MS",
        LOCK + "|0x10|DB_LOCK_TIMEOUT_MS",
        LOCK + "|2147483648|DB_LOCK_TIMEOUT_MS",
        STATEMENT + "|0|DB_STATEMENT_TIMEOUT_MS",
        STATEMENT + "|-1|DB_STATEMENT_TIMEOUT_MS",
        STATEMENT + "|5s|DB_STATEMENT_TIMEOUT_MS",
        STATEMENT + "|2147483648|DB_STATEMENT_TIMEOUT_MS",
        IDLE + "|0|DB_IDLE_IN_TRANSACTION_TIMEOUT_MS",
        IDLE + "|-1|DB_IDLE_IN_TRANSACTION_TIMEOUT_MS",
        IDLE + "|10s|DB_IDLE_IN_TRANSACTION_TIMEOUT_MS",
        IDLE + "|2147483648|DB_IDLE_IN_TRANSACTION_TIMEOUT_MS"
      })
  void rejectsValueThatIsNotPositiveMilliseconds(
      final String property, final String value, final String environmentVariable) {
    final MockEnvironment environment = validEnvironment().withProperty(property, value);

    assertThatThrownBy(() -> DatabaseTimeLimits.from(environment))
        .as("%s=[%s]", property, value)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(environmentVariable)
        .hasMessageContaining("1 以上のミリ秒の整数");
  }

  @Test
  @DisplayName("プロパティがなければ、環境変数の名前を示して失敗する")
  void rejectsMissingProperty() {
    final MockEnvironment environment = new MockEnvironment().withProperty(STATEMENT, "5000");

    assertThatThrownBy(() -> DatabaseTimeLimits.from(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DB_LOCK_TIMEOUT_MS");
  }

  @Test
  @DisplayName("環境変数が未設定なら、その環境変数の名前を示して失敗する")
  void rejectsUnresolvedEnvironmentVariable() {
    final MockEnvironment environment =
        validEnvironment().withProperty(LOCK, "${DB_LOCK_TIMEOUT_MS}");

    assertThatThrownBy(() -> DatabaseTimeLimits.from(environment))
        .hasMessageContaining("DB_LOCK_TIMEOUT_MS");
  }

  @ParameterizedTest(name = "lock_timeout={0}, statement_timeout={1}")
  @DisplayName("lock_timeout が statement_timeout と同じか長ければ失敗する")
  @CsvSource({"5000,5000", "5001,5000"})
  void rejectsLockTimeoutNotShorterThanStatementTimeout(
      final int lockTimeoutMs, final int statementTimeoutMs) {
    assertThatThrownBy(() -> new DatabaseTimeLimits(lockTimeoutMs, statementTimeoutMs, 10_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DB_LOCK_TIMEOUT_MS は DB_STATEMENT_TIMEOUT_MS より短くする");
  }

  @Test
  @DisplayName("lock_timeout が statement_timeout より 1 ミリ秒短ければ受け付ける")
  void acceptsLockTimeoutJustShorterThanStatementTimeout() {
    assertThat(new DatabaseTimeLimits(4999, 5000, 10_000).lockTimeoutMs()).isEqualTo(4999);
  }

  @Test
  @DisplayName("statement_timeout が接続取得の待ち時間と同じなら受け付ける")
  void acceptsStatementTimeoutEqualToConnectionTimeout() {
    final DatabaseTimeLimits limits = new DatabaseTimeLimits(1000, 5000, 10_000);

    limits.requireStatementTimeoutAtMost(5000);

    assertThat(limits.statementTimeoutMs()).isEqualTo(5000);
  }

  @Test
  @DisplayName("statement_timeout が接続取得の待ち時間より長ければ失敗する")
  void rejectsStatementTimeoutLongerThanConnectionTimeout() {
    final DatabaseTimeLimits limits = new DatabaseTimeLimits(1000, 5001, 10_000);

    assertThatThrownBy(() -> limits.requireStatementTimeoutAtMost(5000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DB_STATEMENT_TIMEOUT_MS")
        .hasMessageContaining("connection-timeout");
  }
}
