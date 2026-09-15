package com.example.demo;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.demo.testkit.SharedTestConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.core.functions.Either;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/** アプリケーション起動時の日時、DB、耐障害性、OIDC 配線を検証する統合テスト。 */
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
  }

  @Test
  @DisplayName("time limiter は外部呼び出しを二秒で打ち切る")
  void timeLimiterUsesProjectDefault() {
    final TimeLimiterConfig config = timeLimiterRegistry.getDefaultConfig();

    assertEquals(Duration.ofSeconds(2), config.getTimeoutDuration(), "time limiter の上限が二秒であること");
    assertTrue(config.shouldCancelRunningFuture(), "timeout 時に実行中の Future を cancel すること");
  }

  @Test
  @DisplayName("アプリケーション Clock は UTC 固定である")
  void applicationClockUsesUtc() {
    assertEquals(ZoneOffset.UTC, clock.getZone(), "アプリケーション Clock は UTC 固定であること");
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
}
