package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.EventPublicationResubmitOnce.Target;
import com.example.demo.shared.events.ResubmissionPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
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

  /** 定期の実行を有効にする設定。 */
  private static final String ENABLE_SCHEDULING = ENABLED + "=true";

  /** 定期の実行を無効にする設定。 */
  private static final String DISABLED = ENABLED + "=false";

  /** 手での 1 回の再投入の対象のキー。 */
  private static final String RESUBMIT_ONCE_TARGET =
      EventPublicationResubmissionConfig.RESUBMIT_ONCE_TARGET;

  /** 終了の前に executor のタスクの完了を待つかのキー。 */
  private static final String AWAIT_TERMINATION =
      "spring.task.execution.shutdown.await-termination";

  /** 終了の前に executor のタスクの完了を待つ上限のキー。 */
  private static final String AWAIT_TERMINATION_PERIOD =
      "spring.task.execution.shutdown.await-termination-period";

  /** 妥当な設定値。 */
  private static final String[] VALID = {
    "event-publication.resubmission.wait-age=PT5M",
    "event-publication.resubmission.in-flight-staleness=PT25M",
    "event-publication.resubmission.interval=PT1M",
    "event-publication.resubmission.max-per-run=100",
    "event-publication.resubmission.lock-at-most-for=PT10M",
    "spring.modulith.events.resubmission.abandon-after-attempts=5",
    "event-publication.resubmission.policies.default.multiplier=2",
    "event-publication.resubmission.policies.default.max-wait=PT1H",
    "event-publication.resubmit-once.completion-timeout=PT10M"
  };

  /** 検証対象の設定だけを読み込み、妥当な設定値と、ロックの bean が受け取る依存の mock を与える runner。 */
  private final ApplicationContextRunner runner = bareRunner().withPropertyValues(VALID);

  /** 検証対象の設定とロックの bean が受け取る依存の mock だけを与え、設定値を与えない runner。 */
  private static ApplicationContextRunner bareRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(EventPublicationResubmissionConfig.class)
        // ロックの provider は作るだけで DB に触れないため、依存は mock で足りる。
        .withBean(
            DSLContext.class, () -> Mockito.mock(DSLContext.class, Answers.RETURNS_DEEP_STUBS))
        .withBean(
            PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class));
  }

  @Test
  @DisplayName("enabled が false なら定期の実行を登録しない")
  void disabledDoesNotRegisterScheduling() {
    runner
        .withPropertyValues(DISABLED)
        .run(context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(EventPublicationResubmissionConfig.Scheduling.class));
  }

  @Test
  @DisplayName("enabled が true なら定期の実行を 1 つ登録する")
  void enabledRegistersScheduling() {
    runnerWithResubmitter()
        .withPropertyValues(ENABLE_SCHEDULING)
        .run(context ->
            assertThat(context)
                .hasNotFailed()
                .hasSingleBean(EventPublicationResubmissionConfig.Scheduling.class));
  }

  @Test
  @DisplayName("enabled が true なら resubmitOnce を interval の固定の遅延のタスクとして 1 つ登録する")
  void enabledRegistersResubmitOnceAsFixedDelayTask() {
    runnerWithResubmitter()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withPropertyValues(ENABLE_SCHEDULING)
        .run(context -> {
          final List<FixedDelayTask> tasks = fixedDelayTasks(context);
          assertThat(tasks).as("固定の遅延のタスク").hasSize(1);
          assertThat(tasks.getFirst().getIntervalDuration()).isEqualTo(Duration.ofMinutes(1));
          assertThat(tasks.getFirst().getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(1));
          tasks.getFirst().getRunnable().run();
          Mockito.verify(context.getBean(EventPublicationResubmitter.class)).resubmitOnce();
        });
  }

  @Test
  @DisplayName("enabled が false なら、ほかの定期のジョブが @EnableScheduling を付けても、再投入のタスクを登録しない")
  void disabledDoesNotRegisterResubmitOnceWhenAnotherJobEnablesScheduling() {
    scheduledRunner()
        .withPropertyValues(DISABLED)
        .withUserConfiguration(AnotherScheduledJob.class)
        .run(context -> assertThat(fixedDelayTasks(context)).as("固定の遅延のタスク").isEmpty());
  }

  @Test
  @DisplayName("application.yaml の設定で定期の実行を有効にしても、組み込みの Staleness Monitor の定期のタスクは登録しない")
  void builtInStalenessMonitorIsNotScheduledWithApplicationYaml() {
    scheduledRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(EventPublicationAutoConfiguration.class))
        .withBean(
            EventPublicationRepository.class, () -> Mockito.mock(EventPublicationRepository.class))
        .run(context ->
            assertThat(fixedDelayTasks(context))
                .as("application.yaml の設定の固定の遅延のタスク")
                .singleElement()
                .satisfies(
                    task -> assertThat(task.getRunnable().toString()).contains("resubmitOnce")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"published", "processing", "resubmitted"})
  @DisplayName("組み込みの Staleness Monitor の閾値のどれかを書くと、起動に失敗する")
  void builtInStalenessThresholdFailsStartup(final String threshold) {
    assertStalenessMonitorRejected(
        runner.withPropertyValues(
            DISABLED, "spring.modulith.events.staleness." + threshold + "=PT5M"));
  }

  @Test
  @DisplayName("組み込みの Staleness Monitor の閾値を環境変数で渡しても起動に失敗し、check-interval だけなら起動する")
  void builtInStalenessThresholdFromEnvironmentVariableFailsStartup() {
    assertStalenessMonitorRejected(
        runner
            .withPropertyValues(DISABLED)
            .withInitializer(context ->
                context
                    .getEnvironment()
                    .getPropertySources()
                    .addFirst(
                        new SystemEnvironmentPropertySource(
                            "testEnvironment",
                            Map.of("SPRING_MODULITH_EVENTS_STALENESS_PUBLISHED", "PT5M")))));
    runner
        .withPropertyValues(DISABLED, "spring.modulith.events.staleness.check-interval=PT1M")
        .run(context -> assertThat(context).hasNotFailed());
  }

  private static void assertStalenessMonitorRejected(final ApplicationContextRunner rejected) {
    rejected.run(context ->
        assertThat(context)
            .getFailure()
            .hasRootCauseInstanceOf(BindValidationException.class)
            .rootCause()
            .hasMessageContaining("spring.modulith.events.staleness.published, processing and"));
  }

  /** 定期の実行が呼ぶジョブを mock で足した runner。登録したタスクを動かして、mock が呼ばれることを確かめるテストに使う。 */
  private ApplicationContextRunner runnerWithResubmitter() {
    return runner.withBean(
        EventPublicationResubmitter.class, () -> Mockito.mock(EventPublicationResubmitter.class));
  }

  /**
   * 定期の実行を有効にし、定期の再投入を足した runner。{@code DISABLED} を重ねて無効にもできる。初回の遅延が 1 分あり、テストの間にタスクは動かないため、依存は
   * mock で足りる。
   */
  private ApplicationContextRunner scheduledRunner() {
    return runner
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(EventPublicationResubmitter.class, ResubmissionPolicies.class)
        .withBean(FailedPublicationSweep.class, () -> Mockito.mock(FailedPublicationSweep.class))
        .withBean(
            Clock.class,
            () -> Clock.fixed(Instant.parse("2026-01-01T00:00:00.000000Z"), ZoneOffset.UTC))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(ENABLE_SCHEDULING);
  }

  /** 期限を過ぎた冪等性キーの掃除を有効にする設定のキー。掃除の構成の条件から読み、キーを変えても、このテストが古い文字列を確かめ続けないようにする。 */
  private static String purgeEnabledKey() {
    try {
      return Class.forName(
              "com.example.demo.shared.persistence.IdempotencyKeyPurgeConfig$Scheduling")
          .getAnnotation(ConditionalOnBooleanProperty.class)
          .value()[0];
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("掃除の構成が見つからない", e);
    }
  }

  private static List<FixedDelayTask> fixedDelayTasks(final ApplicationContext context) {
    return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
        .map(ScheduledTask::getTask)
        .filter(FixedDelayTask.class::isInstance)
        .map(FixedDelayTask.class::cast)
        .toList();
  }

  @Test
  @DisplayName("待ち時間が 1 秒より短いと起動に失敗する")
  void zeroWaitAgeFailsStartup() {
    assertBindFailure("event-publication.resubmission.wait-age=PT0S");
  }

  @Test
  @DisplayName("再投入の回数の上限が 2 より小さいと起動に失敗し、2 なら起動する")
  void abandonAfterAttemptsBelowTwoFailsStartup() {
    assertBindFailure("spring.modulith.events.resubmission.abandon-after-attempts=1");
    runner
        .withPropertyValues(
            DISABLED, "spring.modulith.events.resubmission.abandon-after-attempts=2")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("再投入の回数の上限が未設定（Spring Modulith は諦めない）だと起動に失敗する")
  void unsetAbandonAfterAttemptsFailsStartup() {
    bareRunner()
        .withPropertyValues(
            Stream.of(VALID)
                .filter(property ->
                    !property.startsWith(
                        "spring.modulith.events.resubmission.abandon-after-attempts="))
                .toArray(String[]::new))
        .withPropertyValues(DISABLED)
        .run(context ->
            assertThat(context)
                .getFailure()
                .hasRootCauseInstanceOf(BindValidationException.class)
                .rootCause()
                .hasMessageContaining("abandon-after-attempts must be at least 2"));
  }

  @Test
  @DisplayName("倍率が 1 より小さいと起動に失敗する")
  void multiplierBelowOneFailsStartup() {
    assertBindFailure("event-publication.resubmission.policies.default.multiplier=0.5");
  }

  @Test
  @DisplayName("方針の abandon-after-attempts が 1 未満だと起動に失敗し、1 なら起動する")
  void policyAbandonAfterAttemptsBelowOneFailsStartup() {
    assertBindFailure("event-publication.resubmission.policies.default.abandon-after-attempts=0");
    runner
        .withPropertyValues(
            DISABLED, "event-publication.resubmission.policies.default.abandon-after-attempts=1")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("方針の abandon-after-attempts が全体の値以上だと、方針の名前を示して起動に失敗し、1 小さければ起動する")
  void policyAbandonAfterAttemptsNotBelowGlobalFailsStartup() {
    runner
        .withUserConfiguration(ResubmissionPolicies.class)
        .withPropertyValues(
            DISABLED, "event-publication.resubmission.policies.default.abandon-after-attempts=5")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessageContaining("policies.default.abandon-after-attempts (5)")
                .hasMessageContaining("must be less than"));
    runner
        .withUserConfiguration(ResubmissionPolicies.class)
        .withPropertyValues(
            DISABLED, "event-publication.resubmission.policies.default.abandon-after-attempts=4")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("default の方針がないと起動に失敗する")
  void missingDefaultPolicyFailsStartup() {
    bareRunner()
        .withPropertyValues(
            "event-publication.resubmission.wait-age=PT5M",
            "event-publication.resubmission.in-flight-staleness=PT25M",
            "event-publication.resubmission.interval=PT1M",
            "event-publication.resubmission.max-per-run=100",
            "event-publication.resubmission.lock-at-most-for=PT10M",
            "spring.modulith.events.resubmission.abandon-after-attempts=5",
            "event-publication.resubmission.policies.other.multiplier=2",
            "event-publication.resubmission.policies.other.max-wait=PT1H",
            "event-publication.resubmit-once.completion-timeout=PT10M")
        .run(context ->
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
  @DisplayName("処理中の出版の閾値が lock-at-most-for（PT10M）の 2 倍以下だと起動に失敗し、1 秒長ければ起動する")
  void inFlightStalenessNotLongerThanTwiceLockAtMostForFailsStartup() {
    assertBindFailure("event-publication.resubmission.in-flight-staleness=PT20M");
    runner
        .withPropertyValues(DISABLED, "event-publication.resubmission.in-flight-staleness=PT20M1S")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Test
  @DisplayName("設定にない方針の名前を @ResubmissionPolicy に書いたリスナーがあると、名前を示して起動に失敗する")
  void unknownPolicyNameFailsStartup() {
    runner
        .withUserConfiguration(ResubmissionPolicies.class)
        .withBean(UnknownPolicyListener.class)
        .run(context ->
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
        new EventPublicationResubmissionConfig.Policy(2, Duration.ofHours(1), null);
    final Duration waitAge = Duration.ofMinutes(5);

    assertThat(policy.waitBefore(waitAge, 1)).isEqualTo(Duration.ofMinutes(5));
    assertThat(policy.waitBefore(waitAge, 2)).isEqualTo(Duration.ofMinutes(10));
    assertThat(policy.waitBefore(waitAge, 4)).isEqualTo(Duration.ofMinutes(40));
    assertThat(policy.waitBefore(waitAge, 5)).isEqualTo(Duration.ofHours(1));
    assertThat(policy.waitBefore(waitAge, 1_000)).isEqualTo(Duration.ofHours(1));
    assertThat(
            new EventPublicationResubmissionConfig.Policy(1, Duration.ofHours(1), null)
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
    final MockEnvironment normal =
        new MockEnvironment().withProperty(ENABLED, Boolean.TRUE.toString());

    postProcessor.postProcessEnvironment(once, new SpringApplication());
    postProcessor.postProcessEnvironment(normal, new SpringApplication());

    assertThat(once.getProperty("spring.main.web-application-type")).isEqualTo("none");
    assertThat(once.getProperty(ENABLED)).isEqualTo("false");
    assertThat(once.getProperty(purgeEnabledKey())).isEqualTo("false");
    assertThat(once.getProperty(AWAIT_TERMINATION)).isEqualTo("true");
    assertThat(once.getProperty(AWAIT_TERMINATION_PERIOD)).isEqualTo("20s");
    assertThat(normal.getProperty("spring.main.web-application-type")).isNull();
    assertThat(normal.getProperty(ENABLED)).isEqualTo("true");
    assertThat(normal.getProperty(AWAIT_TERMINATION)).isNull();
    assertThat(normal.getProperty(AWAIT_TERMINATION_PERIOD)).isNull();
  }

  @Test
  @DisplayName("手での 1 回の再投入の終了の設定では、文脈を閉じる前に applicationTaskExecutor の処理中のリスナーの完了を待つ")
  void resubmitOnceShutdownWaitsForRunningListener() {
    final AtomicBoolean running = new AtomicBoolean();
    final AtomicBoolean exiting = new AtomicBoolean();
    final AtomicBoolean done = new AtomicBoolean();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
        .withPropertyValues(
            "spring.threads.virtual.enabled=true",
            "spring.task.execution.shutdown.await-termination=true",
            "spring.task.execution.shutdown.await-termination-period=20s")
        .run(context -> {
          // 終了を始めてから終わるタスクで、処理中のリスナーを表す。
          context
              .getBean("applicationTaskExecutor", TaskExecutor.class)
              .execute(() -> {
                running.set(true);
                Awaitility.await("終了を始める").atMost(Duration.ofSeconds(10)).untilTrue(exiting);
                done.set(true);
              });

          Awaitility.await("リスナーが動き始める").atMost(Duration.ofSeconds(10)).untilTrue(running);
          exiting.set(true);
          EventPublicationResubmissionConfig.awaitRunningListeners(context);

          assertThat(done).as("文脈を閉じる前のリスナーの完了").isTrue();
        });
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

  @ParameterizedTest(name = "[{0}] 通常の起動: {1}")
  @CsvSource(
      value = {
        "'', true",
        "' ', true",
        "false, false",
        "FALSE, false",
        "all, false",
        "policy:payment, false"
      },
      quoteCharacter = '\'')
  @DisplayName("手での 1 回の再投入の対象が空白でなければ、Web と定期の実行を止め、runner も作る（false も同じ）")
  void resubmitOnceGatesAgree(final String target, final boolean normalStartup) {
    final MockEnvironment environment =
        new MockEnvironment()
            .withProperty(RESUBMIT_ONCE_TARGET, target)
            .withProperty(ENABLED, Boolean.TRUE.toString());
    new EventPublicationResubmissionConfig.ResubmitOnceEnvironment()
        .postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("spring.main.web-application-type") == null)
        .as("Web を止めない")
        .isEqualTo(normalStartup);
    assertThat(environment.getProperty(ENABLED)).isEqualTo(Boolean.toString(normalStartup));
    assertThat(EventPublicationResubmissionConfig.isResubmitOnceRequested(environment))
        .isEqualTo(!normalStartup);
    runner
        .withBean(
            EventPublicationResubmitOnce.class,
            () -> Mockito.mock(EventPublicationResubmitOnce.class))
        .withInitializer(context ->
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("target", Map.of(RESUBMIT_ONCE_TARGET, target))))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(
                  context.getBeansOfType(ApplicationRunner.class).containsKey("resubmitOnceRunner"))
              .as("runner の bean")
              .isEqualTo(!normalStartup);
        });
  }

  @Test
  @DisplayName("手での 1 回の再投入の対象が設定されていなければ、runner を作らず、Web と定期の実行を止めない")
  void unsetTargetIsNormalStartup() {
    final MockEnvironment environment =
        new MockEnvironment().withProperty(ENABLED, Boolean.TRUE.toString());

    assertThat(EventPublicationResubmissionConfig.isResubmitOnceRequested(environment)).isFalse();
    runner.run(context -> assertThat(context).doesNotHaveBean("resubmitOnceRunner"));
  }

  @Test
  @DisplayName("手での 1 回の再投入の終了コードは、すべて完了なら 0、未完了や例外なら 1 である")
  void resubmitOnceExitCode() {
    final EventPublicationResubmitOnce job = Mockito.mock(EventPublicationResubmitOnce.class);
    Mockito.when(job.run(Target.parse("all")))
        .thenReturn(new EventPublicationResubmitOnce.Outcome(2, 2, 0, 0));
    Mockito.when(job.run(Target.parse("policy:payment")))
        .thenReturn(new EventPublicationResubmitOnce.Outcome(2, 1, 1, 0));
    Mockito.when(job.run(Target.parse("policy:missing")))
        .thenThrow(new IllegalArgumentException("unknown"));

    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "all")).isZero();
    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "policy:payment"))
        .isEqualTo(1);
    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "policy:missing"))
        .isEqualTo(1);
    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, "OrderConfirmed"))
        .as("読めない対象は、job を呼ばずに 1 を返す")
        .isEqualTo(1);
    assertThat(EventPublicationResubmissionConfig.resubmitOnceExitCode(job, null)).isEqualTo(1);
    Mockito.verify(job, Mockito.times(3)).run(Mockito.any());
  }

  @Test
  @DisplayName("定期の実行を有効にしても、@Async の Listener は concurrency-limit を持つ applicationTaskExecutor で動く")
  void schedulingKeepsAsyncListenersOnTheApplicationTaskExecutor() {
    runnerWithResubmitter()
        .withConfiguration(
            AutoConfigurations.of(
                TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
        .withPropertyValues(
            ENABLE_SCHEDULING,
            "spring.threads.virtual.enabled=true",
            "spring.task.execution.simple.concurrency-limit=2")
        .run(context -> {
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
        .withPropertyValues(DISABLED, property)
        .run(context ->
            assertThat(context)
                .getFailure()
                .as("%s の起動の失敗", property)
                .hasRootCauseInstanceOf(BindValidationException.class));
  }

  /** 期限を過ぎた冪等性キーの掃除のように、定期のタスクを持たずに @EnableScheduling だけを付ける別の定期のジョブ。 */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  /* package */ static class AnotherScheduledJob {}

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
