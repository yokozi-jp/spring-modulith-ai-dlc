package com.example.demo.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/** 期限を過ぎた冪等性キーの掃除が、有効なときだけ {@code interval} の固定の遅延のタスクになり、設定の誤りで起動に失敗することを確かめる（ADR-126）。 */
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class IdempotencyKeyPurgeConfigTest {

  /** 検証対象の設定と、初回の遅延の間は動かないジョブの mock だけを与える runner。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
          .withUserConfiguration(IdempotencyKeyPurgeConfig.class)
          .withBean(IdempotencyKeyPurge.class, () -> Mockito.mock(IdempotencyKeyPurge.class))
          .withPropertyValues(
              "idempotency-key.purge.interval=PT1H",
              "idempotency-key.purge.lock-at-most-for=PT2H",
              "idempotency-key.purge.batch-size=1000");

  @Test
  @DisplayName("enabled が true なら purgeOnce を interval の固定の遅延のタスクとして 1 つ登録する")
  void enabledRegistersPurgeOnceAsFixedDelayTask() {
    runner
        .withPropertyValues("idempotency-key.purge.enabled=true")
        .run(context -> {
          final List<FixedDelayTask> tasks = fixedDelayTasks(context);
          assertThat(tasks).as("固定の遅延のタスク").hasSize(1);
          assertThat(tasks.getFirst().getIntervalDuration()).isEqualTo(Duration.ofHours(1));
          assertThat(tasks.getFirst().getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
          assertThat(tasks.getFirst().getRunnable().toString()).contains("purgeOnce");
          tasks.getFirst().getRunnable().run();
          Mockito.verify(context.getBean(IdempotencyKeyPurge.class)).purgeOnce();
        });
  }

  @Test
  @DisplayName("enabled が false なら定期の実行を登録しない")
  void disabledDoesNotRegisterScheduling() {
    runner
        .withPropertyValues("idempotency-key.purge.enabled=false")
        .run(context ->
            assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(IdempotencyKeyPurgeConfig.Scheduling.class));
  }

  @Test
  @DisplayName("ロックを持つ上限が間隔以下だと起動に失敗する")
  void lockAtMostForNotLongerThanIntervalFailsStartup() {
    runner
        .withPropertyValues(
            "idempotency-key.purge.enabled=false", "idempotency-key.purge.lock-at-most-for=PT1H")
        .run(context ->
            assertThat(context).getFailure().hasRootCauseInstanceOf(BindValidationException.class));
  }

  private static List<FixedDelayTask> fixedDelayTasks(final ApplicationContext context) {
    return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
        .map(ScheduledTask::getTask)
        .filter(FixedDelayTask.class::isInstance)
        .map(FixedDelayTask.class::cast)
        .toList();
  }
}
