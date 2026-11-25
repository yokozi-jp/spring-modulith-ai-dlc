package com.example.demo.shared.eventresubmission;

import static com.example.demo.testkit.ScheduledTasks.fixedDelayTasks;
import static com.example.demo.testkit.ScheduledTasks.schedulingRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.demo.testkit.ScheduledTasks.AnotherScheduledJob;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.modulith.events.CompletedEventPublications;

/**
 * archive の行の保持期限の削除が、有効なときだけ {@code interval} の固定の遅延のタスクになり、保持期限が定期の再投入の archive
 * を読み戻す期間（ADR-079）以下なら起動に失敗し、削除の失敗を投げずに記録することを確かめる（ADR-120）。
 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
@ExtendWith(OutputCaptureExtension.class)
class EventPublicationArchiveRetentionConfigTest {

  /** 有効と無効のキー。 */
  private static final String ENABLED = "event-publication.archive-retention.enabled";

  /** 再投入の設定値から決まる、archive を読み戻す期間。 */
  private static final Duration READ_BACK = Duration.ofMinutes(37);

  /** 再投入の設定値。読み戻す期間を {@link #READ_BACK} にする。 */
  private static final EventPublicationResubmissionConfig.Properties RESUBMISSION =
      ResubmissionTestSupport.properties(
          Map.of(
              EventPublicationResubmissionConfig.DEFAULT_POLICY,
              new EventPublicationResubmissionConfig.Policy(2, Duration.ofHours(1), null)));

  /** 検証対象の設定と、再投入の設定値と、初回の遅延の間は動かない Spring Modulith の API の mock だけを与える runner。 */
  private final ApplicationContextRunner runner =
      schedulingRunner()
          .withUserConfiguration(EventPublicationArchiveRetentionConfig.class)
          .withBean(EventPublicationResubmissionConfig.Properties.class, () -> RESUBMISSION)
          .withBean(
              CompletedEventPublications.class,
              () -> Mockito.mock(CompletedEventPublications.class))
          .withPropertyValues("event-publication.archive-retention.interval=PT1H");

  @Test
  @DisplayName("有効で保持期限が読み戻す期間より 1 マイクロ秒長ければ、削除を起動の 5 分後から interval の固定の遅延のタスクとして 1 つ登録する")
  void enabledRegistersDeletionAsFixedDelayTask() {
    final Duration retention = READ_BACK.plusNanos(1_000);
    runner
        .withPropertyValues(
            ENABLED + "=true", "event-publication.archive-retention.retention=" + retention)
        .run(context -> {
          assertThat(fixedDelayTasks(context))
              .as("固定の遅延のタスク")
              .singleElement()
              .satisfies(task -> {
                assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(5));
                assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                task.getRunnable().run();
              });
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
  @DisplayName("削除が DB の失敗と予期しない例外を別のイベントで記録し、どちらも投げずに戻る")
  void deleteExpiredLogsFailuresWithoutThrowing(final CapturedOutput output) {
    final CompletedEventPublications completed = Mockito.mock(CompletedEventPublications.class);
    Mockito.doThrow(new QueryTimeoutException("lock timeout"))
        .doThrow(new IllegalStateException("unexpected"))
        .when(completed)
        .deletePublicationsOlderThan(Mockito.any(Duration.class));
    final EventPublicationArchiveRetentionConfig.Scheduling scheduling =
        new EventPublicationArchiveRetentionConfig.Scheduling(
            completed,
            new EventPublicationArchiveRetentionConfig.Properties(
                Duration.ofDays(7), Duration.ofDays(1)),
            RESUBMISSION);

    assertThatCode(scheduling::deleteExpired).as("DB の失敗").doesNotThrowAnyException();
    assertThatCode(scheduling::deleteExpired).as("予期しない例外").doesNotThrowAnyException();

    assertThat(output.getOut())
        .contains("Event publication archive retention failed")
        .contains("Event publication archive retention crashed");
  }
}
