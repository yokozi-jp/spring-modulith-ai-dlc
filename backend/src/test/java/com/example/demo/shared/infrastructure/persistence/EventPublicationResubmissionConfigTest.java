package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.events.ResubmissionPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link EventPublicationResubmissionConfig} の有効と無効の条件、設定値の検証、方針の待ち時間、ロックの表の描画、手での 1
 * 回の再投入の起動、{@code @Async} の executor を検証する。
 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.TooManyMethods"})
class EventPublicationResubmissionConfigTest {

  /** 定期の実行の有効と無効のキー。 */
  private static final String ENABLED = "event-publication.resubmission.enabled";

  /** 妥当な設定値。 */
  private static final String[] VALID = {
    "event-publication.resubmission.wait-age=PT5M",
    "event-publication.resubmission.interval=PT1M",
    "event-publication.resubmission.max-per-run=100",
    "event-publication.resubmission.lock-at-most-for=PT10M",
    "event-publication.resubmission.policies.default.max-resubmissions=5",
    "event-publication.resubmission.policies.default.multiplier=2",
    "event-publication.resubmission.policies.default.max-wait=PT1H",
    "event-publication.resubmit-once.completion-timeout=PT10M"
  };

  /** 検証対象の設定だけを読み込み、妥当な設定値と、ロックの bean が受け取る依存の mock を与える runner。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(EventPublicationResubmissionConfig.class)
          // ロックの provider は作るだけで DB に触れないため、依存は mock で足りる。
          .withBean(
              DSLContext.class, () -> Mockito.mock(DSLContext.class, Answers.RETURNS_DEEP_STUBS))
          .withBean(
              PlatformTransactionManager.class,
              () -> Mockito.mock(PlatformTransactionManager.class))
          .withPropertyValues(VALID);

  @Test
  @DisplayName("enabled が false なら定期の実行を登録しない")
  void disabledDoesNotRegisterScheduling() {
    runner
        .withPropertyValues(ENABLED + "=false")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(EventPublicationResubmissionConfig.Scheduling.class));
  }

  @Test
  @DisplayName("enabled が true なら定期の実行を 1 つ登録する")
  void enabledRegistersScheduling() {
    runner
        .withPropertyValues(ENABLED + "=true")
        .run(
            context ->
                assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(EventPublicationResubmissionConfig.Scheduling.class));
  }

  @Test
  @DisplayName("enabled が true なら resubmitOnce を interval の固定の遅延のタスクとして 1 つ登録する")
  void enabledRegistersResubmitOnceAsFixedDelayTask() {
    runner
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(EventPublicationResubmitter.class, ResubmissionPolicies.class)
        // 初回の遅延が 1 分あり、テストの間にタスクは動かないため、依存は mock で足りる。
        .withBean(FailedEventPublications.class, () -> Mockito.mock(FailedEventPublications.class))
        .withBean(
            Clock.class,
            () -> Clock.fixed(Instant.parse("2026-01-01T00:00:00.000000Z"), ZoneOffset.UTC))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(ENABLED + "=true")
        .run(
            context -> {
              final List<FixedDelayTask> tasks =
                  context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                      .map(ScheduledTask::getTask)
                      .filter(FixedDelayTask.class::isInstance)
                      .map(FixedDelayTask.class::cast)
                      .toList();
              assertThat(tasks).as("固定の遅延のタスク").hasSize(1);
              assertThat(tasks.getFirst().getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
              assertThat(tasks.getFirst().getInitialDelayDuration())
                  .isEqualTo(Duration.ofMinutes(1));
              assertThat(tasks.getFirst().getRunnable().toString()).contains("resubmitOnce");
            });
  }

  @Test
  @DisplayName("待ち時間が 1 秒より短いと起動に失敗する")
  void zeroWaitAgeFailsStartup() {
    assertBindFailure("event-publication.resubmission.wait-age=PT0S");
  }

  @Test
  @DisplayName("方針の上限の回数が 1 より小さいと起動に失敗する")
  void zeroMaxResubmissionsFailsStartup() {
    assertBindFailure("event-publication.resubmission.policies.default.max-resubmissions=0");
  }

  @Test
  @DisplayName("倍率が 1 より小さいと起動に失敗する")
  void multiplierBelowOneFailsStartup() {
    assertBindFailure("event-publication.resubmission.policies.default.multiplier=0.5");
  }

  @Test
  @DisplayName("default の方針がないと起動に失敗する")
  void missingDefaultPolicyFailsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(EventPublicationResubmissionConfig.class)
        .withBean(
            DSLContext.class, () -> Mockito.mock(DSLContext.class, Answers.RETURNS_DEEP_STUBS))
        .withBean(
            PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class))
        .withPropertyValues(
            "event-publication.resubmission.wait-age=PT5M",
            "event-publication.resubmission.interval=PT1M",
            "event-publication.resubmission.max-per-run=100",
            "event-publication.resubmission.lock-at-most-for=PT10M",
            "event-publication.resubmission.policies.other.max-resubmissions=5",
            "event-publication.resubmission.policies.other.multiplier=2",
            "event-publication.resubmission.policies.other.max-wait=PT1H",
            "event-publication.resubmit-once.completion-timeout=PT10M")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .rootCause()
                    .hasMessageContaining("policies.default is required"));
  }

  @Test
  @DisplayName("待ち時間の上限が wait-age より短いと起動に失敗する")
  void maxWaitShorterThanWaitAgeFailsStartup() {
    assertBindFailure("event-publication.resubmission.policies.default.max-wait=PT1M");
  }

  @Test
  @DisplayName("ロックを持つ上限が間隔以下だと起動に失敗する")
  void lockAtMostForNotLongerThanIntervalFailsStartup() {
    assertBindFailure("event-publication.resubmission.lock-at-most-for=PT1M");
  }

  @Test
  @DisplayName("設定にない方針の名前を @ResubmissionPolicy に書いたリスナーがあると、名前を示して起動に失敗する")
  void unknownPolicyNameFailsStartup() {
    runner
        .withUserConfiguration(ResubmissionPolicies.class)
        .withBean(UnknownPolicyListener.class)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("@ResubmissionPolicy(\"missing\")")
                    .hasMessageContaining("UnknownPolicyListener.on"));
  }

  @Test
  @DisplayName("n 回目の再投入の前の待ち時間は min(wait-age × multiplier^(n-1), max-wait) である")
  void waitBeforeGrowsExponentiallyUpToMaxWait() {
    final EventPublicationResubmissionConfig.Policy policy =
        new EventPublicationResubmissionConfig.Policy(5, 2, Duration.ofHours(1));
    final Duration waitAge = Duration.ofMinutes(5);

    assertThat(policy.waitBefore(waitAge, 1)).isEqualTo(Duration.ofMinutes(5));
    assertThat(policy.waitBefore(waitAge, 2)).isEqualTo(Duration.ofMinutes(10));
    assertThat(policy.waitBefore(waitAge, 4)).isEqualTo(Duration.ofMinutes(40));
    assertThat(policy.waitBefore(waitAge, 5)).isEqualTo(Duration.ofHours(1));
    assertThat(policy.waitBefore(waitAge, 1_000)).isEqualTo(Duration.ofHours(1));
    assertThat(
            new EventPublicationResubmissionConfig.Policy(5, 1, Duration.ofHours(1))
                .waitBefore(waitAge, 5))
        .as("倍率 1")
        .isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  @DisplayName("ロックの表は search_path に頼らず modulith スキーマで描画する")
  void lockTableIsRenderedWithModulithSchema() {
    final DSLContext lockDsl =
        EventPublicationResubmissionConfig.lockDsl(DSL.using(SQLDialect.POSTGRES));

    assertThat(lockDsl.render(lockDsl.selectFrom(DSL.table(DSL.name("shedlock")))))
        .contains("\"modulith\".\"shedlock\"");
  }

  @Test
  @DisplayName("手での 1 回の再投入の対象があると、Web のサーバーと定期の実行を止め、なければ何もしない")
  void resubmitOnceEnvironmentDisablesWebAndScheduling() {
    final EventPublicationResubmissionConfig.ResubmitOnceEnvironment postProcessor =
        new EventPublicationResubmissionConfig.ResubmitOnceEnvironment();
    final MockEnvironment once =
        new MockEnvironment()
            .withProperty("event-publication.resubmit-once.target", "policy:payment")
            .withProperty(ENABLED, "true");
    final MockEnvironment normal = new MockEnvironment().withProperty(ENABLED, "true");

    postProcessor.postProcessEnvironment(once, new SpringApplication());
    postProcessor.postProcessEnvironment(normal, new SpringApplication());

    assertThat(once.getProperty("spring.main.web-application-type")).isEqualTo("none");
    assertThat(once.getProperty(ENABLED)).isEqualTo("false");
    assertThat(normal.getProperty("spring.main.web-application-type")).isNull();
    assertThat(normal.getProperty(ENABLED)).isEqualTo("true");
  }

  @Test
  @DisplayName("手での 1 回の再投入の起動は、spring.factories から読まれる")
  void resubmitOnceEnvironmentIsRegistered() {
    // 他の EnvironmentPostProcessor は Spring Boot が渡す引数を要るため、作れないものは飛ばす。
    assertThat(
            SpringFactoriesLoader.forDefaultResourceLocation()
                .load(
                    EnvironmentPostProcessor.class,
                    SpringFactoriesLoader.ArgumentResolver.none(),
                    SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {})))
        .hasAtLeastOneElementOfType(
            EventPublicationResubmissionConfig.ResubmitOnceEnvironment.class);
  }

  @Test
  @DisplayName("手での 1 回の再投入の対象がなければ、起動の後に再投入して終了する runner を作らない")
  void resubmitOnceRunnerIsCreatedOnlyWithTarget() {
    runner.run(context -> assertThat(context).doesNotHaveBean("resubmitOnceRunner"));
  }

  @Test
  @DisplayName("手での 1 回の再投入の終了コードは、すべて完了なら 0、未完了や例外なら 1 である")
  void resubmitOnceExitCode() {
    final EventPublicationResubmitOnce job = Mockito.mock(EventPublicationResubmitOnce.class);
    Mockito.when(job.run("all")).thenReturn(new EventPublicationResubmitOnce.Outcome(2, 2, 0, 0));
    Mockito.when(job.run("policy:payment"))
        .thenReturn(new EventPublicationResubmitOnce.Outcome(2, 1, 1, 0));
    Mockito.when(job.run("policy:missing")).thenThrow(new IllegalArgumentException("unknown"));

    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "all")).isZero();
    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "policy:payment"))
        .isEqualTo(1);
    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "policy:missing"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("定期の実行を有効にしても、@Async の Listener は concurrency-limit を持つ applicationTaskExecutor で動く")
  void schedulingKeepsAsyncListenersOnTheApplicationTaskExecutor() {
    runner
        .withConfiguration(
            AutoConfigurations.of(
                TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
        .withPropertyValues(
            ENABLED + "=true",
            "spring.threads.virtual.enabled=true",
            "spring.task.execution.simple.concurrency-limit=2")
        .run(
            context -> {
              assertThat(context).hasSingleBean(TaskScheduler.class);
              // Spring Boot 4.1 は taskExecutor の別名を作らず、@Async の executor を AsyncConfigurer で渡す。
              assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
                  .isSameAs(context.getBean("applicationTaskExecutor"));
              assertThat(
                      ((SimpleAsyncTaskExecutor) context.getBean("applicationTaskExecutor"))
                          .getConcurrencyLimit())
                  .isEqualTo(2);
            });
  }

  private void assertBindFailure(final String property) {
    runner
        .withPropertyValues(ENABLED + "=false", property)
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .as("%s の起動の失敗", property)
                    .hasRootCauseInstanceOf(BindValidationException.class));
  }

  /** 設定にない方針の名前を指定したリスナー。 */
  // 受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class UnknownPolicyListener {

    @ApplicationModuleListener
    @ResubmissionPolicy("missing")
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }
}
