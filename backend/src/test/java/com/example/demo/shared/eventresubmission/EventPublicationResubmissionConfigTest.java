package com.example.demo.shared.eventresubmission;

import static com.example.demo.testkit.ScheduledTasks.fixedDelayTasks;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.events.ResubmissionPolicy;
import com.example.demo.testkit.ScheduledTasks.AnotherScheduledJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.config.EventPublicationAutoConfiguration;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;

/**
 * {@link EventPublicationResubmissionConfig} の有効と無効の条件、設定値の検証、方針の待ち時間、{@code @Async} の executor
 * を検証する。
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

  /** 妥当な設定値。 */
  private static final String[] VALID = {
    "event-publication.resubmission.wait-age=PT5M",
    "event-publication.resubmission.in-flight-staleness=PT25M",
    "event-publication.resubmission.interval=PT1M",
    "event-publication.resubmission.max-per-run=100",
    "event-publication.resubmission.lock-at-most-for=PT10M",
    "spring.modulith.events.resubmission.abandon-after-attempts=5",
    "event-publication.resubmission.policies.default.multiplier=2",
    "event-publication.resubmission.policies.default.max-wait=PT1H"
  };

  /** 検証対象の設定だけを読み込み、妥当な設定値を与える runner。 */
  private final ApplicationContextRunner runner = bareRunner().withPropertyValues(VALID);

  /** 検証対象の設定だけを与え、設定値を与えない runner。 */
  private static ApplicationContextRunner bareRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
        .withUserConfiguration(EventPublicationResubmissionConfig.class);
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
            DSLContext.class, () -> Mockito.mock(DSLContext.class, Answers.RETURNS_DEEP_STUBS))
        .withBean(
            Clock.class,
            () -> Clock.fixed(Instant.parse("2026-01-01T00:00:00.000000Z"), ZoneOffset.UTC))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues(ENABLE_SCHEDULING);
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
            "event-publication.resubmission.policies.other.max-wait=PT1H")
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
    runner
        .withPropertyValues(DISABLED, "event-publication.resubmission.lock-at-most-for=PT1M")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOfSatisfying(
                    BindValidationException.class,
                    failure ->
                        assertThat(failure.getValidationErrors().getName())
                            .as("利用者が見る失敗の対象")
                            .hasToString("event-publication.resubmission")));
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
  @DisplayName("1 回の実行の予算は lock-at-most-for の半分である")
  void runBudgetIsHalfOfLockAtMostFor() {
    final EventPublicationResubmissionConfig.Properties properties =
        ResubmissionTestSupport.properties(
            Map.of(
                "default",
                new EventPublicationResubmissionConfig.Policy(2, Duration.ofHours(1), null)));

    assertThat(properties.runBudget()).isEqualTo(properties.lockAtMostFor().dividedBy(2));
  }

  @Test
  @DisplayName("定期の実行を有効にしても、@Async の Listener は applicationTaskExecutor で動く")
  void schedulingKeepsAsyncListenersOnTheApplicationTaskExecutor() {
    runnerWithResubmitter()
        .withConfiguration(
            AutoConfigurations.of(
                TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
        .withPropertyValues(ENABLE_SCHEDULING, "spring.threads.virtual.enabled=true")
        .run(context -> {
          assertThat(context).hasSingleBean(TaskScheduler.class);
          // Spring Boot 4.1 は taskExecutor の別名を作らず、@Async の executor を AsyncConfigurer で渡す。
          assertThat(context.getBean(AsyncConfigurer.class).getAsyncExecutor())
              .isSameAs(context.getBean("applicationTaskExecutor"));
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
