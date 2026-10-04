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

  /** lock_timeout の環境変数。 */
  private static final String LOCK = "DB_LOCK_TIMEOUT_MS";

  /** statement_timeout の環境変数。 */
  private static final String STATEMENT = "DB_STATEMENT_TIMEOUT_MS";

  /** idle_in_transaction_session_timeout の環境変数。 */
  private static final String IDLE = "DB_IDLE_IN_TRANSACTION_TIMEOUT_MS";

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
  @DisplayName("1 以上 2147483647 以下のミリ秒の整数でない値は、環境変数の名前と条件を示して失敗する")
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        LOCK + "|0",
        LOCK + "|-1",
        LOCK + "|abc",
        LOCK + "|1s",
        LOCK + "|\"\"",
        LOCK + "|\" 1000\"",
        LOCK + "|0x10",
        LOCK + "|2147483648",
        STATEMENT + "|0",
        STATEMENT + "|-1",
        STATEMENT + "|5s",
        STATEMENT + "|2147483648",
        IDLE + "|0",
        IDLE + "|-1",
        IDLE + "|10s",
        IDLE + "|2147483648"
      })
  void rejectsValueOutsideMillisecondRange(final String name, final String value) {
    final MockEnvironment environment = validEnvironment().withProperty(name, value);

    assertThatThrownBy(() -> DatabaseTimeLimits.from(environment))
        .as("%s=[%s]", name, value)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(name + " は 1 以上 2147483647 以下のミリ秒の整数にする");
  }

  @Test
  @DisplayName("int の最大値は受け付ける")
  void acceptsIntMaxValue() {
    final MockEnvironment environment =
        validEnvironment().withProperty(STATEMENT, "2147483647").withProperty(IDLE, "2147483647");

    assertThat(DatabaseTimeLimits.from(environment).statementTimeoutMs())
        .isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  @DisplayName("環境変数が未設定なら、その環境変数の名前を示して失敗する")
  void rejectsMissingEnvironmentVariable() {
    final MockEnvironment environment = new MockEnvironment().withProperty(STATEMENT, "5000");

    assertThatThrownBy(() -> DatabaseTimeLimits.from(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DB_LOCK_TIMEOUT_MS を設定する");
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
