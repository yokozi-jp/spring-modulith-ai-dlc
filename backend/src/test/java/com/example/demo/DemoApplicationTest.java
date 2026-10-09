package com.example.demo;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.testkit.SharedTestConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** アプリケーション起動時の日時、DB、耐障害性、OIDC 配線を検証する統合テスト。 */
// 起動時の配線を設定ごとに一つのテストで検証するため、メソッドの数と結合する型の数の上限を外す。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
@SpringBootTest
@Import(SharedTestConfiguration.class)
class DemoApplicationTest {

  /** UTC 固定を検証する対象のアプリケーション {@code Clock}。 */
  @Autowired private Clock clock;

  /** DB セッションのタイムゾーンと {@code timestamptz} 往復を検証するための {@code JdbcTemplate}。 */
  @Autowired private JdbcTemplate jdbcTemplate;

  /** HikariCP の実効 pool 設定を検証する対象の {@code HikariDataSource}。 */
  @Autowired private HikariDataSource hikariDataSource;

  /** circuit breaker の実効既定値を検証する registry。 */
  @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

  /** retry の実効既定値を検証する registry。 */
  @Autowired private RetryRegistry retryRegistry;

  /** time limiter の実効既定値を検証する registry。 */
  @Autowired private TimeLimiterRegistry timeLimiterRegistry;

  /** 仮想スレッドを使うアプリケーション共通 executor。 */
  @Autowired
  @Qualifier("applicationTaskExecutor") private AsyncTaskExecutor applicationTaskExecutor;

  /** PKCE パラメータを検証する対象の認可リクエストリゾルバ。 */
  @Autowired private OAuth2AuthorizationRequestResolver authorizationRequestResolver;

  /** resilience4j の注釈が掛かることを検証する決済代行の Client。 */
  @Autowired private PaymentGateway paymentGateway;

  /** 起動確認の対象となる {@code ApplicationContext}。 */
  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("ApplicationContext が起動する")
  void contextLoads() {
    assertNotNull(applicationContext, "ApplicationContext が起動できること");
  }

  @Test
  @DisplayName("HikariCP は環境ごとの接続予算と待機上限を使う")
  void hikariUsesConfiguredCapacityBudget() {
    final int expectedMaximumPoolSize =
        applicationContext
            .getEnvironment()
            .getRequiredProperty("DB_POOL_MAXIMUM_SIZE", Integer.class);
    final long expectedConnectionTimeout =
        applicationContext
            .getEnvironment()
            .getRequiredProperty("DB_POOL_CONNECTION_TIMEOUT_MS", Long.class);

    assertEquals(
        expectedMaximumPoolSize,
        hikariDataSource.getMaximumPoolSize(),
        "HikariCP maximum-pool-size が DB 接続予算と一致すること");
    assertEquals(
        expectedConnectionTimeout,
        hikariDataSource.getConnectionTimeout(),
        "HikariCP connection-timeout が設定値と一致すること");
  }

  @Test
  @DisplayName("アプリケーション executor は仮想スレッドを使う")
  @SuppressWarnings("PMD.DoNotUseThreads") // executor が作る実スレッドの種別を確認するために必要。
  void applicationExecutorUsesVirtualThreads()
      throws ExecutionException, InterruptedException, TimeoutException {
    final boolean isVirtual =
        applicationTaskExecutor.submit(() -> Thread.currentThread().isVirtual()).get(5, SECONDS);

    assertTrue(isVirtual, "applicationTaskExecutor のタスクが仮想スレッドで動くこと");
  }

  @Test
  @DisplayName("アプリケーション executor の同時実行数は、DB の接続の pool の半分以下に制限する")
  void applicationExecutorConcurrencyLeavesConnectionsForRequests() {
    final SimpleAsyncTaskExecutor executor =
        assertInstanceOf(
            SimpleAsyncTaskExecutor.class, applicationTaskExecutor, "仮想スレッドの executor");
    final int limit = executor.getConcurrencyLimit();
    final int poolSize = hikariDataSource.getMaximumPoolSize();

    assertTrue(
        limit >= 1 && limit * 2 <= poolSize,
        () -> "同時実行数 " + limit + " が 1 以上で、pool " + poolSize + " の半分以下であること");
  }

  @Test
  @DisplayName("circuit breaker の既定値が障害の連鎖を制限する")
  void circuitBreakerUsesProjectDefaults() {
    final CircuitBreakerConfig config = circuitBreakerRegistry.getDefaultConfig();

    assertEquals(
        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED,
        config.getSlidingWindowType(),
        "circuit breaker が回数ベースの sliding window を使うこと");
    assertEquals(20, config.getSlidingWindowSize(), "circuit breaker の評価窓が 20 回であること");
    assertEquals(10, config.getMinimumNumberOfCalls(), "circuit breaker が 10 回から評価を始めること");
    assertEquals(50.0F, config.getFailureRateThreshold(), "failure rate の閾値が 50% であること");
    assertEquals(
        30_000L,
        config.getWaitIntervalFunctionInOpenState().apply(1),
        "circuit breaker の open 継続時間が 30 秒であること");
    assertEquals(
        5, config.getPermittedNumberOfCallsInHalfOpenState(), "half-open で許可する試行が 5 回であること");
  }

  @Test
  @DisplayName("retry は既定で無効かつ冪等操作だけ exponential backoff を使う")
  void retryUsesFailSafeDefaults() {
    final RetryConfig defaultConfig = retryRegistry.getDefaultConfig();
    final RetryConfig idempotentConfig =
        retryRegistry
            .getConfiguration("idempotent")
            .orElseThrow(() -> new AssertionError("retry config=idempotent が登録されていること"));
    final IntervalBiFunction<Object> intervalFunction =
        Objects.requireNonNull(
            idempotentConfig.getIntervalBiFunction(), "idempotent retry の interval function");
    final Either<Throwable, Object> retryableFailure =
        Either.left(new IllegalStateException("一時障害のテスト入力"));
    final long firstRetryDelay =
        Objects.requireNonNull(intervalFunction.apply(1, retryableFailure), "一回目の retry 待機時間");
    final long secondRetryDelay =
        Objects.requireNonNull(intervalFunction.apply(2, retryableFailure), "二回目の retry 待機時間");

    assertEquals(1, defaultConfig.getMaxAttempts(), "retry の既定試行回数が一回であること");
    assertEquals(3, idempotentConfig.getMaxAttempts(), "冪等操作の最大試行回数が三回であること");
    assertEquals(200L, firstRetryDelay, "冪等操作の初回 retry 待機が 200 ミリ秒であること");
    assertEquals(400L, secondRetryDelay, "冪等操作の retry 待機が倍率 2 で増えること");
    final Predicate<Throwable> retryable = idempotentConfig.getExceptionPredicate();
    assertEquals(
        Map.of(429, true, 503, true, 500, false, 400, false),
        Map.of(
            429, retryable.test(httpError(HttpStatus.TOO_MANY_REQUESTS)),
            503, retryable.test(httpError(HttpStatus.SERVICE_UNAVAILABLE)),
            500, retryable.test(httpError(HttpStatus.INTERNAL_SERVER_ERROR)),
            400, retryable.test(httpError(HttpStatus.BAD_REQUEST))),
        "冪等操作が ADR-019 の一時障害の 429 と 503 だけを再試行し、500 と 400 を再試行しないこと");
  }

  @Test
  @DisplayName("決済代行の Client に payment-gateway の circuit breaker と retry が掛かる")
  void paymentGatewayIsGuardedByResilience4j() {
    // YAML の instance は注釈がなくても registry に作られるため、呼び出しの件数の増加で aspect が掛かったことを確かめる。
    // 請求は compose-test の WireMock（.env.test の PAYMENT_GATEWAY_BASE_URL）の共有のスタブが成功で返す。
    final CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("payment-gateway");
    final Retry retry = retryRegistry.retry("payment-gateway");
    final long callsBefore = circuitBreaker.getMetrics().getNumberOfSuccessfulCalls();
    final long retryBefore = retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt();

    paymentGateway.charge(new OrderId(UUID.randomUUID()), new Money(new BigDecimal("1.00")));

    assertEquals(
        callsBefore + 1,
        circuitBreaker.getMetrics().getNumberOfSuccessfulCalls(),
        "payment-gateway の circuit breaker が成功の呼び出しを 1 件数えること");
    assertEquals(
        retryBefore + 1,
        retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt(),
        "payment-gateway の retry が再試行なしの成功を 1 件数えること");
    assertEquals(
        1, retry.getRetryConfig().getMaxAttempts(), "payment-gateway の retry の試行が 1 回であること");
  }

  @Test
  @DisplayName("決済代行の呼び出しの最悪の時間は、トランザクション中の待機の上限より短い")
  void paymentGatewayWorstCaseFitsIdleInTransactionTimeout() {
    // Listener はトランザクションの中で決済代行を呼ぶ（ADR-050）。待つ間に idle_in_transaction_session_timeout を超えると、
    // PostgreSQL が接続を切り、請求の結果を記録できない（ADR-055）。
    final Duration connectTimeout =
        applicationContext
            .getEnvironment()
            .getRequiredProperty("payment-gateway.connect-timeout", Duration.class);
    final Duration readTimeout =
        applicationContext
            .getEnvironment()
            .getRequiredProperty("payment-gateway.read-timeout", Duration.class);
    final RetryConfig retryConfig = retryRegistry.retry("payment-gateway").getRetryConfig();
    final IntervalBiFunction<Object> intervalFunction =
        Objects.requireNonNull(
            retryConfig.getIntervalBiFunction(), "payment-gateway の retry の interval function");
    final Either<Throwable, Object> failure = Either.left(new IllegalStateException("一時障害のテスト入力"));
    final int attempts = retryConfig.getMaxAttempts();
    long worstCaseMillis = attempts * (connectTimeout.toMillis() + readTimeout.toMillis());
    for (int retry = 1; retry < attempts; retry++) {
      worstCaseMillis += Objects.requireNonNull(intervalFunction.apply(retry, failure), "再試行の待ち");
    }
    final long idleTimeoutMillis =
        applicationContext
            .getEnvironment()
            .getRequiredProperty("DB_IDLE_IN_TRANSACTION_TIMEOUT_MS", Long.class);
    final long worstCase = worstCaseMillis;

    assertTrue(
        worstCase < idleTimeoutMillis,
        () ->
            "決済代行の最悪の時間 "
                + worstCase
                + " ms が DB_IDLE_IN_TRANSACTION_TIMEOUT_MS "
                + idleTimeoutMillis
                + " ms より短いこと");
  }

  @Test
  @DisplayName("time limiter は外部呼び出しを二秒で打ち切る")
  void timeLimiterUsesProjectDefault() {
    final TimeLimiterConfig config = timeLimiterRegistry.getDefaultConfig();

    assertEquals(Duration.ofSeconds(2), config.getTimeoutDuration(), "time limiter の上限が二秒であること");
    assertTrue(config.shouldCancelRunningFuture(), "timeout 時に実行中の Future を cancel すること");
  }

  @Test
  @DisplayName("アプリケーション Clock は UTC 固定でマイクロ秒単位である")
  void applicationClockUsesUtc() {
    assertEquals(ZoneOffset.UTC, clock.getZone(), "アプリケーション Clock は UTC 固定であること");
    final Instant now = clock.instant();
    assertEquals(0, now.getNano() % 1_000, "アプリケーション Clock はマイクロ秒単位の値を返すこと");
  }

  @Test
  @DisplayName("DB セッションのタイムゾーンは UTC である")
  void databaseSessionUsesUtc() {
    assertEquals(
        "UTC",
        jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class),
        "DB セッションのタイムゾーンは UTC であること");
  }

  @Test
  @DisplayName("DB セッションの待ち時間の上限は環境変数の値であり、ロック待ちは文の実行より短い")
  void databaseSessionUsesConfiguredTimeLimits() {
    final Map<String, String> settings =
        Map.of(
            "lock_timeout", "DB_LOCK_TIMEOUT_MS",
            "statement_timeout", "DB_STATEMENT_TIMEOUT_MS",
            "idle_in_transaction_session_timeout", "DB_IDLE_IN_TRANSACTION_TIMEOUT_MS");
    settings.forEach(
        (name, environmentVariable) -> {
          final String shown = jdbcTemplate.queryForObject("SHOW " + name, String.class);
          // SHOW は 1000 を 1s のように単位付きで返すため、interval に変換してミリ秒で比べる。
          assertEquals(
              applicationContext.getEnvironment().getRequiredProperty(environmentVariable),
              jdbcTemplate.queryForObject(
                  "SELECT (EXTRACT(EPOCH FROM CAST(? AS interval)) * 1000)::bigint::text",
                  String.class,
                  shown),
              () -> "DB セッションの " + name + " (" + shown + ") が " + environmentVariable + " と一致すること");
        });
    final long lockTimeout =
        applicationContext.getEnvironment().getRequiredProperty("DB_LOCK_TIMEOUT_MS", Long.class);
    final long statementTimeout =
        applicationContext
            .getEnvironment()
            .getRequiredProperty("DB_STATEMENT_TIMEOUT_MS", Long.class);

    assertTrue(
        lockTimeout < statementTimeout,
        () ->
            "lock_timeout は statement_timeout より短いこと: lock_timeout="
                + lockTimeout
                + ", statement_timeout="
                + statementTimeout);
  }

  @Test
  @DisplayName("Instant が timestamptz 往復で保存される")
  void instantRoundTripsThroughTimestampWithTimeZone() {
    final Instant expected = Instant.parse("2026-09-07T06:18:42.567123Z");
    final OffsetDateTime actual =
        jdbcTemplate.queryForObject(
            "SELECT CAST(? AS TIMESTAMP WITH TIME ZONE)",
            OffsetDateTime.class,
            expected.atOffset(ZoneOffset.UTC));

    assertNotNull(actual, "timestamptz へキャストした結果が取得できること");
    assertEquals(expected, actual.toInstant(), "timestamptz 往復で Instant が保存されること");
  }

  @Test
  @DisplayName("認可リクエストが PKCE (S256) を使う")
  void authorizationRequestUsesPkceS256() {
    final MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/web");
    request.setServletPath("/oauth2/authorization/web");

    final OAuth2AuthorizationRequest authorizationRequest =
        authorizationRequestResolver.resolve(request);

    assertNotNull(authorizationRequest, "認可リクエストが解決されること");
    assertNotNull(
        authorizationRequest.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE),
        "PKCE の code_challenge が付与されること");
    assertEquals(
        "S256",
        authorizationRequest
            .getAdditionalParameters()
            .get(PkceParameterNames.CODE_CHALLENGE_METHOD),
        "PKCE の code_challenge_method が S256 であること");
    assertNotNull(
        authorizationRequest.getAttributes().get(PkceParameterNames.CODE_VERIFIER),
        "PKCE の code_verifier が保持されること");
  }

  /** 状態のコードに合う RestClient の HTTP の例外を作る。 */
  private static RuntimeException httpError(final HttpStatus status) {
    return status.is4xxClientError()
        ? HttpClientErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null)
        : HttpServerErrorException.create(status, "", HttpHeaders.EMPTY, new byte[0], null);
  }
}
