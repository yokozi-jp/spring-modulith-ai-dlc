package com.example.demo.testkit.idempotency;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import com.example.demo.testkit.idempotency.Violation.Kind;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.transaction.support.TransactionOperations;

/**
 * イベントを出版し、リスナーの出版の状態を {@code event_publication} と {@code event_publication_archive} から読んで待つ。
 *
 * <p>出版は、Spring Modulith の {@code Scenario#publish} と同じく、トランザクションの中で {@link
 * ApplicationEventPublisher} に渡す。完了は出版の状態の表で待つため、{@code Scenario} の待ちと検証は使わない。
 */
/* package */ final class PublicationRegistry {

  /** 出版の完了を待つ上限。門で止めた配信が {@link CommitGate#HOLD_LIMIT} で諦めて失敗になるのを待てるよう、その 2 倍にする。 */
  /* package */ static final Duration COMPLETION_TIMEOUT = CommitGate.HOLD_LIMIT.multipliedBy(2);

  /** 状態を読み直す間隔。2 回目のロックの待ちを見つけてから 1 回目を通すまでを短くするため、短くする。 */
  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

  /** 出版の状態を読む jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 出版を囲むトランザクション。 */
  private final TransactionOperations transactions;

  /** イベントを出版する。 */
  private final ApplicationEventPublisher publisher;

  /** イベントを出版のレジストリと同じ形で直列化する。 */
  private final EventSerializer serializer;

  /* package */ PublicationRegistry(
      final DSLContext dsl,
      final TransactionOperations transactions,
      final ApplicationEventPublisher publisher,
      final EventSerializer serializer) {
    this.dsl = dsl;
    this.transactions = transactions;
    this.publisher = publisher;
    this.serializer = serializer;
  }

  /**
   * イベントの同じ本文の別のインスタンスを、トランザクションの中で出版する。
   *
   * <p>別のインスタンスにするのは、Spring Modulith 2.2.0 が処理中の出版をイベントのインスタンスの同一性とリスナーで引くためである。同じインスタンスを同時に 2
   * 回出版すると、1 回目の完了が 2 回目の記録を消し、2 回目の失敗が {@code FAILED} にならない。本番の重複（再投入、発行元の再発行）は別のインスタンスである。
   */
  /* package */ void publish(final Object event) {
    final Object copy = serializer.deserialize(serializer.serialize(event), event.getClass());
    transactions.executeWithoutResult(status -> publisher.publishEvent(copy));
  }

  /** リスナーの出版の状態ごとの数。未完了と archive を 1 つの文で読み、時点をそろえる。 */
  /* package */ Publications read(final ListenerId listener) {
    final Table<Record1<String>> all =
        dsl.select(EVENT_PUBLICATION.STATUS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.LISTENER_ID.eq(listener.value()))
            .unionAll(
                dsl.select(EVENT_PUBLICATION_ARCHIVE.STATUS)
                    .from(EVENT_PUBLICATION_ARCHIVE)
                    .where(EVENT_PUBLICATION_ARCHIVE.LISTENER_ID.eq(listener.value())))
            .asTable("publication");
    final Field<String> status = Objects.requireNonNull(all.field(0, String.class), "status");
    final Field<Integer> count = DSL.count();
    return new Publications(
        dsl.select(status, count).from(all).groupBy(status).fetch().stream()
            .collect(
                Collectors.toMap(
                    row ->
                        EventPublication.Status.valueOf(
                            Objects.requireNonNull(row.value1(), "event_publication.status")),
                    Record2::value2)));
  }

  /** リスナーの出版の状態が条件を満たすか上限を過ぎるまで待ち、最後に読んだ状態を返す。上限を過ぎたかは、呼び出し側が状態から判定する。 */
  /* package */ Publications await(
      final ListenerId listener, final Duration timeout, final Predicate<Publications> done) {
    try {
      return Awaitility.await("publications of " + listener)
          .atMost(timeout)
          .pollInterval(POLL_INTERVAL)
          .until(() -> read(listener), done);
    } catch (ConditionTimeoutException e) {
      return read(listener);
    }
  }

  /**
   * リスナーの出版の、状態ごとの数。未完了の {@code event_publication} と {@code event_publication_archive} を合わせて数える。
   *
   * <p>完了は archive の {@code COMPLETED} だけである。archive には、方針の {@code abandon-after-attempts} に達して諦めた
   * {@code ABANDONED} も移るため、これを {@code FAILED} と同じ失敗に数える。
   *
   * @param counts 状態 → 出版の数
   */
  /* package */ record Publications(Map<EventPublication.Status, Integer> counts) {

    /* package */ Publications {
      counts = Map.copyOf(counts);
    }

    /** {@code start} の後に完了した出版の数。 */
    /* package */ int completedSince(final Publications start) {
      return since(start, EventPublication.Status.COMPLETED);
    }

    /** {@code start} の後に失敗した（{@code FAILED} か {@code ABANDONED} になった）出版の数。 */
    /* package */ int failedSince(final Publications start) {
      return since(start, EventPublication.Status.FAILED)
          + since(start, EventPublication.Status.ABANDONED);
    }

    /** {@code start} の後に完了したか失敗した出版の数。 */
    /* package */ int settledSince(final Publications start) {
      return completedSince(start) + failedSince(start);
    }

    /**
     * {@code start} の後に、出版がちょうど {@code expected} 個完了し、失敗がないか。
     *
     * @return 失敗があれば {@link Kind#DELIVERY_FAILED}、完了が {@code expected} 個でなければ {@link
     *     Kind#PUBLICATION_NOT_COMPLETED}、どちらでもなければ空
     */
    /* package */ Optional<Kind> unsettledSince(final Publications start, final int expected) {
      if (failedSince(start) > 0) {
        return Optional.of(Kind.DELIVERY_FAILED);
      }
      return completedSince(start) == expected
          ? Optional.empty()
          : Optional.of(Kind.PUBLICATION_NOT_COMPLETED);
    }

    /** {@code start} からの変化の説明。 */
    /* package */ String describeSince(final Publications start) {
      return "completed+"
          + completedSince(start)
          + ", failed+"
          + failedSince(start)
          + ", counts="
          + counts;
    }

    private int since(final Publications start, final EventPublication.Status status) {
      return counts.getOrDefault(status, 0) - start.counts.getOrDefault(status, 0);
    }
  }
}
