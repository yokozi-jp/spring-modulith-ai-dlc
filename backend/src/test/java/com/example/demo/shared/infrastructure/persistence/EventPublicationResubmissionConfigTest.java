package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** {@link EventPublicationResubmissionConfig} の有効と無効の条件、設定値の検証、{@code @Async} の executor を検証する。 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class EventPublicationResubmissionConfigTest {

  /** 検証対象の設定だけを読み込み、妥当な設定値を与える runner。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(EventPublicationResubmissionConfig.class)
          .withPropertyValues(
              "event-publication.resubmission.wait-age=PT5M",
              "event-publication.resubmission.interval=PT1M",
              "event-publication.resubmission.max-resubmissions=5",
              "event-publication.resubmission.max-per-run=100");

  @Test
  @DisplayName("enabled が false なら定期の実行を登録しない")
  void disabledDoesNotRegisterScheduling() {
    runner
        .withPropertyValues("event-publication.resubmission.enabled=false")
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
        .withPropertyValues("event-publication.resubmission.enabled=true")
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
        .withUserConfiguration(EventPublicationResubmitter.class)
        // 初回の遅延が 1 分あり、テストの間にタスクは動かないため、依存は mock で足りる。
        .withBean(DSLContext.class, () -> Mockito.mock(DSLContext.class))
        .withBean(FailedEventPublications.class, () -> Mockito.mock(FailedEventPublications.class))
        .withBean(
            Clock.class,
            () -> Clock.fixed(Instant.parse("2026-01-01T00:00:00.000000Z"), ZoneOffset.UTC))
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withPropertyValues("event-publication.resubmission.enabled=true")
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
    runner
        .withPropertyValues(
            "event-publication.resubmission.enabled=false",
            "event-publication.resubmission.wait-age=PT0S")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .as("wait-age=PT0S の起動の失敗")
                    .hasRootCauseInstanceOf(BindValidationException.class));
  }

  @Test
  @DisplayName("上限の回数が 1 より小さいと起動に失敗する")
  void zeroMaxResubmissionsFailsStartup() {
    runner
        .withPropertyValues(
            "event-publication.resubmission.enabled=false",
            "event-publication.resubmission.max-resubmissions=0")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .as("max-resubmissions=0 の起動の失敗")
                    .hasRootCauseInstanceOf(BindValidationException.class));
  }

  @Test
  @DisplayName("定期の実行を有効にしても、@Async の Listener は concurrency-limit を持つ applicationTaskExecutor で動く")
  void schedulingKeepsAsyncListenersOnTheApplicationTaskExecutor() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(EventPublicationResubmissionConfig.class)
        .withPropertyValues(
            "event-publication.resubmission.enabled=true",
            "event-publication.resubmission.wait-age=PT5M",
            "event-publication.resubmission.interval=PT1M",
            "event-publication.resubmission.max-resubmissions=5",
            "event-publication.resubmission.max-per-run=100",
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
}
