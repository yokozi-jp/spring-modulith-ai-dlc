package com.example.demo.testkit.idempotency;

import static com.example.demo.testkit.idempotency.PublicationRegistry.COMPLETION_TIMEOUT;

import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.idempotency.ExternalCallRecorder.ExternalCall;
import com.example.demo.testkit.idempotency.PublicationRegistry.Publications;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.springframework.core.env.Environment;

/**
 * 同時の 2 回の検査。1 回目をコミットの直前の {@link CommitGate} で止め、2 回目が 1 回目のロックを待つか門に届くまで待ってから 1 回目を通す。1
 * 回目だけがコミットされた時点の中身を「1 回だけ届けたとき」とし、2 回目のコミットの後と比べる。
 */
/* package */ final class ConcurrentCheck {

  /**
   * 2 回目が 1 回目のロックを待つか門に届くまで待つ上限。
   *
   * <p>Issue の「待つ上限は {@code lock_timeout} より短くする」の意図は、2 回目が {@code lock_timeout}（{@code
   * DB_LOCK_TIMEOUT_MS}、ADR-055）で {@code ConflictException} にならないことである。これは、2 回目がロックを待ち始めたのを {@link
   * PublicationRegistry} の読み直しの間隔で見つけ、すぐ 1 回目を通すことで守る。2 回目がロックを待つ時間は読み直しの間隔ほどで、{@code lock_timeout}
   * より十分に短い。この上限が区切るのは 2 回目が待ち始めるまでの時間（非同期の配信の開始と CI の負荷を含む）であり、{@code lock_timeout} に合わせると負荷で偽の
   * {@link Kind#SECOND_NOT_CONTENDED} になる。1 回目を {@link CommitGate#HOLD_LIMIT} で諦める前に通せるよう、そこから 2
   * 秒の余裕を引く。
   */
  /* package */ static final Duration CONTENTION_LIMIT = CommitGate.HOLD_LIMIT.minusSeconds(2);

  /** 違反の説明の、出版の状態の欄。 */
  private static final String REGISTRY = ", registry=";

  /** 違反の説明の、2 回目の競合の欄。 */
  private static final String SECOND = " (second delivery ";

  /** 同時の 2 回で届ける配信の数。 */
  private static final int TWO = 2;

  /** テナントを登録する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 出版と、出版の状態の読み取り。 */
  private final PublicationRegistry registry;

  /** コミットの直前の門。 */
  private final CommitGate gate;

  /** 外部システムの呼び出しの記録。 */
  private final ExternalCallRecorder calls;

  /** 業務の表を所有者の接続で読む設定。 */
  private final Environment environment;

  /* package */ ConcurrentCheck(
      final DSLContext dsl,
      final PublicationRegistry registry,
      final CommitGate gate,
      final ExternalCallRecorder calls,
      final Environment environment) {
    this.dsl = dsl;
    this.registry = registry;
    this.gate = gate;
    this.calls = calls;
    this.environment = environment;
  }

  /**
   * 前提を作り、競合させて比べる。止めた配信は、戻る前に必ず通す。
   *
   * <p>前提を作るテナントを {@code m_tenant} に登録する。業務の表は {@code m_tenant} のテナントごとに読むためである。
   */
  /* package */ List<Violation> run(final ListenerExample example) {
    TestTenancy.member(dsl, AppUsers.USER_ID, AsTenant.TENANT.value(), "MEMBER");
    final ListenerId listener = example.listener();
    final Object event = example.prepare(AsTenant.TENANT);
    calls.reset();
    final CommitGate.Hold hold = gate.close(listener);
    try {
      return new Race(listener, event, hold, registry.read(listener)).run();
    } finally {
      gate.open();
    }
  }

  /** 1 回目を止めている間の、2 回目の配信の競合。 */
  private enum Contention {
    /** ロックを待たずに、コミットの直前の門に届いた。 */
    REACHED_GATE("reached the commit gate without waiting for a lock"),
    /** 1 回目がコミットする前に例外で終わった。 */
    FAILED("failed before the first committed"),
    /** 1 回目が持つロックを待っている。 */
    WAITING_FOR_LOCK("waited for a lock held by the first");

    /** 違反の説明に書く文。 */
    private final String description;

    Contention(final String description) {
      this.description = description;
    }

    @Override
    public String toString() {
      return description;
    }
  }

  /** 1 つのイベントの、同時の 2 回の配信。 */
  private final class Race {

    /** 届けるリスナー。 */
    private final ListenerId listener;

    /** 2 回届けるイベント。 */
    private final Object event;

    /** 2 つの配信を止める門。 */
    private final CommitGate.Hold hold;

    /** 1 回目の前の出版の状態。 */
    private final Publications start;

    private Race(
        final ListenerId listener,
        final Object event,
        final CommitGate.Hold hold,
        final Publications start) {
      this.listener = listener;
      this.event = event;
      this.hold = hold;
      this.start = start;
    }

    private List<Violation> run() {
      registry.publish(event);
      final Publications first =
          registry.await(
              listener,
              COMPLETION_TIMEOUT,
              state -> hold.arrivals() > 0 || state.settledSince(start) > 0);
      if (hold.arrivals() == 0) {
        return List.of(
            new Violation(
                listener,
                Kind.FIRST_NOT_HELD,
                "the first delivery did not stop before its commit: outsideTransaction="
                    + hold.outsideTransaction()
                    + ", failures="
                    + hold.failures()
                    + REGISTRY
                    + first.describeSince(start)));
      }
      final Set<ExternalCall> once = calls.distinct();
      registry.publish(event);
      // 競合は 1 回目を止めている間は消えないため、待ちを抜けた後に分け直しても同じ競合になる。
      final Publications contended =
          registry.await(listener, CONTENTION_LIMIT, state -> contention(state).isPresent());
      return contention(contended)
          .map(second -> commitBothAndCompare(once, second))
          .orElseGet(() ->
              List.of(
                  new Violation(
                      listener,
                      Kind.SECOND_NOT_CONTENDED,
                      "the second delivery neither waited for a lock, reached the commit gate, nor"
                          + " failed within "
                          + CONTENTION_LIMIT
                          + REGISTRY
                          + contended.describeSince(start))));
    }

    private Optional<Contention> contention(final Publications now) {
      if (hold.arrivals() >= TWO) {
        return Optional.of(Contention.REACHED_GATE);
      }
      if (now.failedSince(start) > 0) {
        return Optional.of(Contention.FAILED);
      }
      if (hold.waitersOnFirst() > 0) {
        return Optional.of(Contention.WAITING_FOR_LOCK);
      }
      return Optional.empty();
    }

    /** 1 回目をコミットさせて中身を読み、2 回目をコミットさせて比べる。 */
    private List<Violation> commitBothAndCompare(
        final Set<ExternalCall> once, final Contention second) {
      hold.releaseNext();
      final Publications afterFirst =
          registry.await(
              listener,
              COMPLETION_TIMEOUT,
              state ->
                  state.failedSince(start) > 0
                      || (state.completedSince(start) > 0 && hold.arrivals() >= TWO));
      if (afterFirst.failedSince(start) > 0) {
        return List.of(
            new Violation(
                listener,
                Kind.DELIVERY_FAILED,
                "a delivery failed after the first was allowed to commit"
                    + SECOND
                    + second
                    + "): failures="
                    + hold.failures()
                    + REGISTRY
                    + afterFirst.describeSince(start)));
      }
      if (afterFirst.completedSince(start) == 0) {
        return List.of(
            new Violation(
                listener,
                Kind.PUBLICATION_NOT_COMPLETED,
                "the first delivery did not complete after it was allowed to commit"
                    + SECOND
                    + second
                    + ")"
                    + REGISTRY
                    + afterFirst.describeSince(start)));
      }
      if (hold.arrivals() < TWO) {
        return List.of(
            new Violation(
                listener,
                Kind.SECOND_NOT_AT_COMMIT,
                "the second delivery did not reach its commit after the first committed"
                    + SECOND
                    + second
                    + ")"
                    + REGISTRY
                    + afterFirst.describeSince(start)));
      }
      final TableRows single = TableRows.read(environment);
      hold.releaseNext();
      final Publications end =
          registry.await(listener, COMPLETION_TIMEOUT, state -> state.settledSince(start) >= TWO);
      final Optional<String> diff = single.diff(TableRows.read(environment));
      final Set<ExternalCall> twice = calls.distinct();
      return Stream.of(
              end.unsettledSince(start, TWO)
                  .map(kind ->
                      new Violation(
                          listener,
                          kind,
                          "the second delivery did not complete after it was allowed to commit"
                              + SECOND
                              + second
                              + "): failures="
                              + hold.failures()
                              + REGISTRY
                              + end.describeSince(start))),
              diff.map(rows ->
                  new Violation(
                      listener,
                      Kind.CONCURRENT_RESULT_DIFFERS,
                      "event="
                          + event
                          + " changed rows after the first delivery had committed"
                          + SECOND
                          + second
                          + "):"
                          + rows)),
              Violation.when(
                  !once.equals(twice),
                  listener,
                  Kind.EXTERNAL_CALLS_DIFFER,
                  () -> "concurrent: one delivery=" + once + ", two deliveries=" + twice))
          .flatMap(Optional::stream)
          .toList();
    }
  }
}
