package com.example.demo.testkit.idempotency;

import static com.example.demo.testkit.idempotency.PublicationRegistry.COMPLETION_TIMEOUT;

import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.idempotency.ExternalCallRecorder.ExternalCall;
import com.example.demo.testkit.idempotency.PublicationRegistry.Publications;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
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
   * {@link Kind#SECOND_NOT_CONTENDED} になる。1 回目を止めている間に 1 回目を通せるよう、{@link CommitGate#HOLD_LIMIT}
   * より短い固定値にする。
   */
  /* package */ static final Duration CONTENTION_LIMIT = Duration.ofSeconds(3);

  /** 違反の説明の、出版の状態の欄。 */
  private static final String REGISTRY = ", registry=";

  /** 同時の 2 回で届ける配信の数。 */
  private static final int TWO = 2;

  /** 出版と、出版の状態とロックの待ちの読み取り。 */
  private final PublicationRegistry registry;

  /** コミットの直前の門。 */
  private final CommitGate gate;

  /** 外部システムの呼び出しの記録。 */
  private final ExternalCallRecorder calls;

  /** 業務の表を所有者の接続で読む設定。 */
  private final Environment environment;

  /* package */ ConcurrentCheck(
      final PublicationRegistry registry,
      final CommitGate gate,
      final ExternalCallRecorder calls,
      final Environment environment) {
    this.registry = registry;
    this.gate = gate;
    this.calls = calls;
    this.environment = environment;
  }

  /** 前提を作り、競合させて比べる。止めた配信は、戻る前に必ず通す。 */
  /* package */ List<Violation> run(final ListenerExample example) {
    final ListenerId listener = example.listener();
    final Object event = example.prepare(AsTenant.TENANT);
    calls.reset();
    final CommitGate.Hold hold = gate.close(listener);
    try {
      return race(listener, event, hold);
    } finally {
      gate.open();
    }
  }

  private List<Violation> race(
      final ListenerId listener, final Object event, final CommitGate.Hold hold) {
    final Publications start = registry.read(listener);
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
                  + first.since(start)));
    }
    final Set<ExternalCall> once = calls.distinct();
    registry.publish(event);
    final SecondDelivery second =
        PublicationRegistry.until(
            "the second delivery of " + listener + " contending with the first",
            CONTENTION_LIMIT,
            () -> classify(hold, start, registry.read(listener)),
            state -> !(state instanceof SecondDelivery.Neither));
    if (second instanceof SecondDelivery.Neither) {
      return List.of(
          new Violation(
              listener, Kind.SECOND_NOT_CONTENDED, second + " within " + CONTENTION_LIMIT));
    }
    return commitBothAndCompare(listener, event, hold, start, once, second);
  }

  private SecondDelivery classify(
      final CommitGate.Hold hold, final Publications start, final Publications now) {
    if (registry.waitersOn(hold.firstPid()) > 0) {
      return new SecondDelivery.WaitingForLock();
    }
    if (hold.arrivals() >= TWO) {
      return new SecondDelivery.ReachedGate();
    }
    if (now.failed() > start.failed()) {
      return new SecondDelivery.Failed();
    }
    return new SecondDelivery.Neither(now.since(start));
  }

  /** 1 回目をコミットさせて中身を読み、2 回目をコミットさせて比べる。 */
  private List<Violation> commitBothAndCompare(
      final ListenerId listener,
      final Object event,
      final CommitGate.Hold hold,
      final Publications start,
      final Set<ExternalCall> once,
      final SecondDelivery second) {
    hold.releaseNext();
    final Publications afterFirst =
        registry.await(
            listener,
            COMPLETION_TIMEOUT,
            state ->
                state.archived() > start.archived()
                    && (hold.arrivals() >= TWO || state.failed() > start.failed()));
    if (afterFirst.failed() > start.failed()) {
      return List.of(
          new Violation(
              listener,
              Kind.CONCURRENT_DELIVERY_FAILED,
              "a delivery failed after the first was allowed to commit (second delivery "
                  + second
                  + "): failures="
                  + hold.failures()
                  + REGISTRY
                  + afterFirst.since(start)));
    }
    if (afterFirst.archived() == start.archived() || hold.arrivals() < TWO) {
      return List.of(
          new Violation(
              listener,
              Kind.PUBLICATION_NOT_COMPLETED,
              "after the first was allowed to commit, the first did not complete or the second"
                  + " did not reach its commit (second delivery "
                  + second
                  + "): arrivals="
                  + hold.arrivals()
                  + REGISTRY
                  + afterFirst.since(start)));
    }
    final TableRows single = TableRows.read(environment);
    hold.releaseNext();
    final Publications end =
        registry.await(listener, COMPLETION_TIMEOUT, state -> state.settledSince(start) >= TWO);
    final Optional<String> diff = single.diff(TableRows.read(environment));
    final Set<ExternalCall> twice = calls.distinct();
    return Stream.of(
            Violation.when(
                end.failed() > start.failed(),
                listener,
                Kind.CONCURRENT_DELIVERY_FAILED,
                () ->
                    "the second delivery failed at its commit (second delivery "
                        + second
                        + "): failures="
                        + hold.failures()
                        + REGISTRY
                        + end.since(start)),
            Violation.when(
                end.failed() == start.failed() && end.archived() - start.archived() != TWO,
                listener,
                Kind.PUBLICATION_NOT_COMPLETED,
                () -> "the second delivery did not complete: registry=" + end.since(start)),
            diff.map(rows ->
                new Violation(
                    listener,
                    Kind.CONCURRENT_RESULT_DIFFERS,
                    "event="
                        + event
                        + " changed rows after the first delivery had committed (second delivery "
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

  /** 1 回目を止めている間の、2 回目の配信の状態。 */
  /* package */ sealed interface SecondDelivery {

    /** 1 回目が持つロックを待っている。 */
    record WaitingForLock() implements SecondDelivery {
      @Override
      public String toString() {
        return "waited for a lock held by the first";
      }
    }

    /** ロックを待たずに、コミットの直前の門に届いた。 */
    record ReachedGate() implements SecondDelivery {
      @Override
      public String toString() {
        return "reached the commit gate without waiting for a lock";
      }
    }

    /** 1 回目がコミットする前に例外で終わった。 */
    record Failed() implements SecondDelivery {
      @Override
      public String toString() {
        return "failed before the first committed";
      }
    }

    /**
     * ロックを待たず、門に届かず、失敗もしていない。
     *
     * @param registry 最後に読んだ出版の状態の、1 回目の前からの変化
     */
    record Neither(String registry) implements SecondDelivery {
      @Override
      public String toString() {
        return "the second delivery neither waited for a lock, reached the commit gate, nor failed:"
            + " registry="
            + registry;
      }
    }
  }
}
