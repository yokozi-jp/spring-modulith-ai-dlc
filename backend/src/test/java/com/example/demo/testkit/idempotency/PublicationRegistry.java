package com.example.demo.testkit.idempotency;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record3;
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

  /** 出版の完了を待つ上限。{@link CommitGate#HOLD_LIMIT} 以上にする。 */
  /* package */ static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(10);

  /** 状態を読み直す間隔。2 回目のロックの待ちを見つけてから 1 回目を通すまでを短くするため、短くする。 */
  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

  /** 出版の状態とロックの待ちを読む jOOQ のコンテキスト。 */
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

  /** リスナーの出版の状態。未完了と完了を 1 つの文で読み、時点をそろえる。 */
  /* package */ Publications read(final ListenerId listener) {
    final Field<Boolean> archived = DSL.field(DSL.name("archived"), Boolean.class);
    final List<Record3<Boolean, String, Integer>> rows =
        dsl.select(DSL.inline(false).as(archived), EVENT_PUBLICATION.STATUS, DSL.count())
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.LISTENER_ID.eq(listener.value()))
            .groupBy(EVENT_PUBLICATION.STATUS)
            .unionAll(
                dsl.select(
                        DSL.inline(true).as(archived),
                        DSL.inline(EventPublication.Status.COMPLETED.name()),
                        DSL.count())
                    .from(EVENT_PUBLICATION_ARCHIVE)
                    .where(EVENT_PUBLICATION_ARCHIVE.LISTENER_ID.eq(listener.value())))
            .fetch();
    return new Publications(
        rows.stream().filter(Record3::value1).mapToInt(Record3::value3).sum(),
        rows.stream()
            .filter(row -> !row.value1())
            .collect(
                Collectors.toMap(
                    row ->
                        EventPublication.Status.valueOf(
                            Objects.requireNonNull(row.value2(), "event_publication.status")),
                    Record3::value3)));
  }

  /** リスナーの出版の状態が条件を満たすか上限を過ぎるまで待ち、最後に読んだ状態を返す。 */
  /* package */ Publications await(
      final ListenerId listener, final Duration timeout, final Predicate<Publications> done) {
    return until("publications of " + listener, timeout, () -> read(listener), done);
  }

  /** 読んだ値が条件を満たすか上限を過ぎるまで待ち、最後に読んだ値を返す。上限を過ぎたかは、呼び出し側が値から判定する。 */
  /* package */ static <T> T until(
      final String alias, final Duration timeout, final Supplier<T> read, final Predicate<T> done) {
    try {
      return Awaitility.await(alias)
          .atMost(timeout)
          .pollInterval(POLL_INTERVAL)
          .until(read::get, done);
    } catch (ConditionTimeoutException e) {
      return read.get();
    }
  }

  /** セッション {@code pid} が持つロックを待っているセッションの数。待っているのが誰を待つかで絞り、無関係なロックの待ちを数えない。 */
  /* package */ int waitersOn(final int pid) {
    return dsl.fetchCount(
        DSL.table(DSL.name("pg_catalog", "pg_stat_activity")),
        DSL.val(pid)
            .eq(
                DSL.any(
                    DSL.function(
                        DSL.name("pg_catalog", "pg_blocking_pids"),
                        Integer[].class,
                        DSL.field(DSL.name("pid"), Integer.class)))));
  }

  /**
   * リスナーの出版の数。
   *
   * @param archived 完了した出版の数
   * @param incomplete 未完了の出版の状態ごとの数
   */
  /* package */ record Publications(
      int archived, Map<EventPublication.Status, Integer> incomplete) {

    /* package */ Publications {
      incomplete = Map.copyOf(incomplete);
    }

    /** {@code FAILED} の出版の数。 */
    /* package */ int failed() {
      return incomplete.getOrDefault(EventPublication.Status.FAILED, 0);
    }

    /** {@code start} の後に完了したか失敗した出版の数。 */
    /* package */ int settledSince(final Publications start) {
      return archived - start.archived + failed() - start.failed();
    }

    /** {@code start} の後に、出版がちょうど 1 つ完了し、失敗がないか。 */
    /* package */ boolean completedOnceSince(final Publications start) {
      return archived == start.archived + 1 && failed() == start.failed();
    }

    /** {@code start} からの変化の説明。 */
    /* package */ String since(final Publications start) {
      return "archived+"
          + (archived - start.archived)
          + ", failed+"
          + (failed() - start.failed())
          + ", incomplete="
          + incomplete;
    }
  }
}
