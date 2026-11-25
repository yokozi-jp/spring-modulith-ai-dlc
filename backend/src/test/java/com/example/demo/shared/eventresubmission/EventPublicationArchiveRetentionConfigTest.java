package com.example.demo.shared.eventresubmission;

import static com.example.demo.testkit.ScheduledTasks.fixedDelayTasks;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * archive の行の保持期限の削除が、有効なときだけ {@code interval} の固定の遅延のタスクになり、保持期限が定期の再投入の archive
 * を読み戻す期間（ADR-079）以下なら起動に失敗することを確かめる（ADR-120）。
 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class EventPublicationArchiveRetentionConfigTest {

  /** 有効と無効のキー。 */
  private static final String ENABLED = "event-publication.archive-retention.enabled";

  /** 再投入の設定から決まる、archive を読み戻す期間。{@code max(3 × 10 分, 25 分 + 10 分 + 2 × 1 分)} の 37 分である。 */
  private static final Duration READ_BACK = Duration.ofMinutes(37);

  /** 再投入の設定値。読み戻す期間を {@link #READ_BACK} にする。 */
  private static final EventPublicationResubmissionConfig.Properties RESUBMISSION =
      new EventPublicationResubmissionConfig.Properties(
          Duration.ofMinutes(5),
          Duration.ofMinutes(25),
          Duration.ofMinutes(1),
          100,
          Duration.ofMinutes(10),
          Map.of(
              EventPublicationResubmissionConfig.DEFAULT_POLICY,
              new EventPublicationResubmissionConfig.Policy(2, Duration.ofHours(1), null)));

  /** 検証対象の設定と、再投入の設定値と、初回の遅延の間は動かない Spring Modulith の API の mock だけを与える runner。 */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
          .withUserConfiguration(EventPublicationArchiveRetentionConfig.class)
          .withBean(EventPublicationResubmissionConfig.Properties.class, () -> RESUBMISSION)
          .withBean(
              CompletedEventPublications.class,
              () -> Mockito.mock(CompletedEventPublications.class))
          .withPropertyValues("event-publication.archive-retention.interval=PT1H");

  @Test
  @DisplayName("有効で保持期限が読み戻す期間より 1 マイクロ秒長ければ、削除を interval の固定の遅延のタスクとして 1 つ登録する")
  void enabledRegistersDeletionAsFixedDelayTask() {
    final Duration retention = READ_BACK.plusNanos(1_000);
    runner
        .withPropertyValues(
            ENABLED + "=true", "event-publication.archive-retention.retention=" + retention)
        .run(context -> {
          final List<FixedDelayTask> tasks = fixedDelayTasks(context);
          assertThat(tasks).as("固定の遅延のタスク").hasSize(1);
          assertThat(tasks.getFirst().getIntervalDuration()).isEqualTo(Duration.ofHours(1));
          assertThat(tasks.getFirst().getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
          tasks.getFirst().getRunnable().run();
          Mockito.verify(context.getBean(CompletedEventPublications.class))
              .deletePublicationsOlderThan(retention);
        });
  }

  @Test
  @DisplayName("有効で保持期限が読み戻す期間と同じなら、両方の値を示して起動に失敗する")
  void enabledWithRetentionNotLongerThanReadBackFailsStartup() {
    runner
        .withPropertyValues(
            ENABLED + "=true", "event-publication.archive-retention.retention=PT37M")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention (PT37M)")
                .hasMessageContaining("read-back period of the resubmission (PT37M)"));
  }

  @Test
  @DisplayName("無効なら、保持期限が読み戻す期間より短くても起動し、ほかの定期のジョブが @EnableScheduling を付けても削除のタスクを登録しない")
  void disabledDoesNotRegisterDeletion() {
    runner
        .withPropertyValues(
            ENABLED + "=false", "event-publication.archive-retention.retention=PT1M")
        .withUserConfiguration(AnotherScheduledJob.class)
        .run(context -> {
          assertThat(context)
              .hasNotFailed()
              .doesNotHaveBean(EventPublicationArchiveRetentionConfig.Scheduling.class);
          assertThat(fixedDelayTasks(context)).as("固定の遅延のタスク").isEmpty();
        });
  }

  @Test
  @DisplayName("application.yaml の保持期限と間隔で有効にすると、テストの環境の再投入の設定で起動し、1 日ごとのタスクを登録する")
  void applicationYamlStartsWhenEnabled() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                ValidationAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
        .withUserConfiguration(
            EventPublicationResubmissionConfig.class, EventPublicationArchiveRetentionConfig.class)
        // ロックの provider は作るだけで DB に触れないため、依存は mock で足りる。
        .withBean(
            DSLContext.class, () -> Mockito.mock(DSLContext.class, Answers.RETURNS_DEEP_STUBS))
        .withBean(
            PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class))
        .withBean(
            CompletedEventPublications.class, () -> Mockito.mock(CompletedEventPublications.class))
        .withPropertyValues(ENABLED + "=true", "event-publication.resubmission.enabled=false")
        .run(context ->
            assertThat(fixedDelayTasks(context))
                .singleElement()
                .satisfies(
                    task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofDays(1))));
  }

  /** 期限を過ぎた冪等性キーの掃除のように、定期のタスクを持たずに {@code @EnableScheduling} だけを付ける別の定期のジョブ。 */
  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  /* package */ static class AnotherScheduledJob {}
}
