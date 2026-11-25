package com.example.demo.shared.eventresubmission;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.shared.eventresubmission.EventPublicationResubmitOnce.Target;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.MutableClock;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.OnceProbe;
import com.example.demo.shared.eventresubmission.ResubmissionProbes.OnceProbeListener;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.CapturedLogRecords;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * 方針の {@code abandon-after-attempts: 1} のリスナーの出版を、最初の配信の失敗で {@code ABANDONED}
 * にし、定期の再投入が再投入しないことを検証する（ADR-079）。
 *
 * <p>全体の {@code abandon-after-attempts} は 2 なので、方針がなければ最初の失敗は {@code FAILED} に残り、定期の再投入の対象になる。
 */
@ResubmissionTestSupport.SharedContext
@EnableScenarios
class EventPublicationResubmitterAbandonPolicyTest {

  /** 最初の配信で諦めたときの ERROR の本文。 */
  private static final String ABANDONED_ON_FIRST_DELIVERY =
      "Event publication abandoned on first delivery";

  /** 検証対象の定期のジョブ。 */
  @Autowired private EventPublicationResubmitter resubmitter;

  /** 手での 1 回の再投入。 */
  @Autowired private EventPublicationResubmitOnce resubmitOnce;

  /** 常に失敗するテストのリスナー。 */
  @Autowired private OnceProbeListener listener;

  /** 出版と archive の行を読み書きする。 */
  @Autowired private DSLContext dsl;

  /** 出版の時刻を決める時計。 */
  @Autowired private MutableClock clock;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** この文脈の OpenTelemetry。 */
  @Autowired private OpenTelemetry openTelemetry;

  @BeforeEach
  void setUp() {
    // 別のコンテキストが appender の送り先を差し替えていても、このコンテキストの記録を読む。
    OpenTelemetryAppender.install(openTelemetry);
    clock.set(ResubmissionProbes.T0);
  }

  /** 拡張の後始末の前に、この型の出版の行を消す。 */
  @AfterEach
  void deleteRows() {
    dsl.deleteFrom(EVENT_PUBLICATION).execute();
    dsl.deleteFrom(EVENT_PUBLICATION_ARCHIVE).execute();
  }

  @Test
  @DisplayName(
      "最初の配信が失敗した出版は、completion_attempts 1 の ABANDONED で archive に移り、リスナーの ID を持つ ERROR を 1 つ出す")
  void firstFailureAbandonsWithError(final Scenario scenario) {
    final String id = abandonedOnFirstDelivery(scenario);

    assertThat(archived(id).value1()).isEqualTo(EventPublication.Status.ABANDONED.name());
    assertThat(archived(id).value2()).as("再投入していない").isEqualTo(1);
    assertThat(dsl.fetchCount(EVENT_PUBLICATION, EVENT_PUBLICATION.SERIALIZED_EVENT.contains(id)))
        .as("未完了の表に残らない")
        .isZero();
    assertThat(listener.calls(id)).isEqualTo(1);
    final List<LogRecordData> errors = errorsOf(archivedId(id));
    assertThat(errors).hasSize(1);
    assertThat(errors.getFirst().getSeverity()).isEqualTo(Severity.ERROR);
    assertThat(
            errors
                .getFirst()
                .getAttributes()
                .get(AttributeKey.stringKey("event_publication.resubmission_policy")))
        .isEqualTo(ResubmissionProbes.ONCE_POLICY);
  }

  @Test
  @DisplayName(
      "Runbook の SQL で FAILED に戻した出版は、待ち時間を過ぎても定期の再投入が再投入せず、手での再投入だけが再投入して、また失敗すると ABANDONED に戻る")
  void restoredPublicationIsResubmittedOnlyByHand(final Scenario scenario) {
    final String id = abandonedOnFirstDelivery(scenario);
    final UUID publication = archivedId(id);
    restoreAsRunbook(publication);
    // 配信の途中で落ちて古い出版として FAILED に戻った出版も、同じ completion_attempts 1 の FAILED の行になる。
    clock.set(ResubmissionProbes.T0.plus(Duration.ofDays(1)));

    ResubmissionTestSupport.runJob(dsl, resubmitter);

    assertThat(
            dsl.select(EVENT_PUBLICATION.STATUS)
                .from(EVENT_PUBLICATION)
                .where(EVENT_PUBLICATION.ID.eq(publication))
                .fetchSingle(EVENT_PUBLICATION.STATUS))
        .as("定期の再投入は RESUBMITTED に進めない")
        .isEqualTo(EventPublication.Status.FAILED.name());

    ResubmissionTestSupport.expireLock(dsl);
    resubmitOnce.run(new Target.Policy(ResubmissionProbes.ONCE_POLICY));

    assertThat(listener.calls(id)).as("最初の配信と手での再投入").isEqualTo(2);
    assertThat(archived(id).value1()).isEqualTo(EventPublication.Status.ABANDONED.name());
    assertThat(errorsOf(publication)).as("手での再投入の失敗では出さない").hasSize(1);
  }

  /** {@link OnceProbe} を発行し、最初の配信の失敗で archive に移るのを待って、イベントの ID を返す。 */
  private String abandonedOnFirstDelivery(final Scenario scenario) {
    final String id = UUID.randomUUID().toString();
    scenario
        .publish(new OnceProbe(id, AsTenant.TENANT))
        .andWaitForStateChange(
            () -> archived(id).value1(), EventPublication.Status.ABANDONED.name()::equals)
        .andVerify(done -> {});
    return id;
  }

  /** Runbook の「上限に達した出版の戻し方」の SQL で、archive の行を未完了の表へ FAILED で戻す。 */
  private void restoreAsRunbook(final UUID publication) {
    dsl.execute(
        """
        WITH moved AS (
          DELETE FROM modulith.event_publication_archive
           WHERE id = ?
             AND status = 'ABANDONED'
          RETURNING id, listener_id, event_type, serialized_event, publication_date,
                    completion_attempts, last_resubmission_date
        )
        INSERT INTO modulith.event_publication
               (id, listener_id, event_type, serialized_event, publication_date,
                status, completion_date, completion_attempts, last_resubmission_date)
        SELECT id, listener_id, event_type, serialized_event, publication_date,
               'FAILED', NULL, completion_attempts, last_resubmission_date
          FROM moved
        """,
        publication);
  }

  private Record2<String, Integer> archived(final String id) {
    return dsl.select(
            EVENT_PUBLICATION_ARCHIVE.STATUS, EVENT_PUBLICATION_ARCHIVE.COMPLETION_ATTEMPTS)
        .from(EVENT_PUBLICATION_ARCHIVE)
        .where(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT.contains(id))
        .fetchOne();
  }

  private UUID archivedId(final String id) {
    return dsl.select(EVENT_PUBLICATION_ARCHIVE.ID)
        .from(EVENT_PUBLICATION_ARCHIVE)
        .where(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT.contains(id))
        .fetchSingle(EVENT_PUBLICATION_ARCHIVE.ID);
  }

  private List<LogRecordData> errorsOf(final UUID publication) {
    return capturedLogRecords.withBody(ABANDONED_ON_FIRST_DELIVERY).stream()
        .filter(record ->
            publication
                .toString()
                .equals(record.getAttributes().get(AttributeKey.stringKey("event_publication.id"))))
        .toList();
  }
}
