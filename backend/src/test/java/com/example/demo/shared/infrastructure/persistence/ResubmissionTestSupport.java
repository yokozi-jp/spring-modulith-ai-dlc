package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.CommittedDatabaseTest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** 再投入のテストで使う、Spring の文脈の設定、ロックの期限、出版の行、上限到達の ERROR の操作。 */
final class ResubmissionTestSupport {

  private ResubmissionTestSupport() {}

  /** 再投入のテストが同じ設定で Spring のテストの文脈を共有するための合成アノテーション。 */
  /* package */ @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @CommittedDatabaseTest
  @Import(EventPublicationResubmitterTest.ProbeConfiguration.class)
  @TestPropertySource(
      properties = {
        "event-publication.resubmission.wait-age=PT5M",
        "event-publication.resubmission.interval=PT2S",
        "event-publication.resubmission.lock-at-most-for=PT3S",
        "event-publication.resubmission.max-per-run=100",
        "event-publication.resubmission.policies.default.max-resubmissions=1",
        "event-publication.resubmission.policies.probe.max-resubmissions=2",
        "event-publication.resubmission.policies.probe.multiplier=2",
        "event-publication.resubmission.policies.probe.max-wait=PT20M",
        // リスナーが進めない変異で、手での再投入が application.yaml の PT10M まで待たないようにする。
        "event-publication.resubmit-once.completion-timeout=PT15S"
      })
  @interface SharedContext {}

  /** 処理を仮想スレッドで呼び、その結果を返す。 */
  // テストのスレッドが待たずに、別のスレッドの処理の途中を操作するため。
  @SuppressWarnings("PMD.DoNotUseThreads")
  /* package */ static <T> CompletableFuture<T> onVirtualThread(final Supplier<T> task) {
    return CompletableFuture.supplyAsync(task, runnable -> Thread.ofVirtual().start(runnable));
  }

  /** ロックの期限を過去にする。 */
  /* package */ static void expireLock(final DSLContext dsl) {
    CleanGeneratedTablesExtension.expireLocks(dsl);
  }

  /** {@code test.event} の出版の行を作り、その ID を返す。 */
  /* package */ static UUID insertPublication(
      final DSLContext dsl,
      final UUID id,
      final String listenerId,
      final Instant publicationDate,
      final String status,
      final int completionAttempts) {
    dsl.insertInto(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.ID, id)
        .set(EVENT_PUBLICATION.LISTENER_ID, listenerId)
        .set(EVENT_PUBLICATION.EVENT_TYPE, "test.event")
        .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{}")
        .set(EVENT_PUBLICATION.PUBLICATION_DATE, publicationDate)
        .set(EVENT_PUBLICATION.STATUS, status)
        .set(EVENT_PUBLICATION.COMPLETION_ATTEMPTS, completionAttempts)
        .execute();
    return id;
  }

  /** {@code publicationId} の出版についての、上限に達した出版の ERROR。 */
  /* package */ static List<LogRecordData> exhaustedErrors(
      final CapturedLogRecords capturedLogRecords, final String publicationId) {
    return capturedLogRecords.withBody("Event publication resubmission exhausted").stream()
        .filter(
            record ->
                publicationId.equals(
                    record.getAttributes().get(AttributeKey.stringKey("event_publication.id"))))
        .toList();
  }
}
