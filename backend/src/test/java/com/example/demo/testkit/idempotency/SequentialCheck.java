package com.example.demo.testkit.idempotency;

import static com.example.demo.testkit.idempotency.PublicationRegistry.COMPLETION_TIMEOUT;

import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.idempotency.ExternalCallRecorder.ExternalCall;
import com.example.demo.testkit.idempotency.PublicationRegistry.Publications;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.core.env.Environment;

/**
 * 順の 2 回の検査。前提を 2 つのテナントに作り、既定でないほうのテナント（{@link ListenerIdempotencyChecker#OTHER_TENANT}）のイベントを順に 2
 * 回届け、両方のテナントの行と外部システムの呼び出しを比べる。
 */
/* package */ final class SequentialCheck {

  /** 出版と、出版の状態の読み取り。 */
  private final PublicationRegistry registry;

  /** 外部システムの呼び出しの記録。 */
  private final ExternalCallRecorder calls;

  /** 業務の表を所有者の接続で読む設定。 */
  private final Environment environment;

  /* package */ SequentialCheck(
      final PublicationRegistry registry,
      final ExternalCallRecorder calls,
      final Environment environment) {
    this.registry = registry;
    this.calls = calls;
    this.environment = environment;
  }

  /* package */ List<Violation> run(final ListenerExample example) {
    final ListenerId listener = example.listener();
    example.prepare(AsTenant.TENANT);
    final Object event = example.prepare(ListenerIdempotencyChecker.OTHER_TENANT);
    calls.reset();
    final TableRows before = TableRows.read(environment);
    final Publications start = registry.read(listener);

    registry.publish(event);
    final Publications first =
        registry.await(listener, COMPLETION_TIMEOUT, state -> state.settledSince(start) > 0);
    final TableRows afterFirst = TableRows.read(environment);
    final Set<ExternalCall> callsFirst = calls.distinct();
    if (!first.completedOnceSince(start)) {
      return List.of(
          new Violation(
              listener,
              Kind.PUBLICATION_NOT_COMPLETED,
              "first delivery: event=" + event + ", registry=" + first.since(start)));
    }

    registry.publish(event);
    final Publications second =
        registry.await(listener, COMPLETION_TIMEOUT, state -> state.settledSince(first) > 0);
    final Optional<String> secondDiff = afterFirst.diff(TableRows.read(environment));
    final Set<ExternalCall> callsSecond = calls.distinct();
    return Stream.of(
            Violation.when(
                before.diff(afterFirst).isEmpty(),
                listener,
                Kind.FIRST_DELIVERY_CHANGED_NOTHING,
                () -> "event=" + event + " changed no row in any tenant"),
            Violation.when(
                !second.completedOnceSince(first),
                listener,
                Kind.PUBLICATION_NOT_COMPLETED,
                () -> "second delivery: event=" + event + ", registry=" + second.since(first)),
            secondDiff.map(diff ->
                new Violation(
                    listener,
                    Kind.SECOND_DELIVERY_CHANGED_ROWS,
                    "event=" + event + " changed rows on the second delivery:" + diff)),
            Violation.when(
                !callsFirst.equals(callsSecond),
                listener,
                Kind.EXTERNAL_CALLS_DIFFER,
                () ->
                    "sequential: after the first delivery="
                        + callsFirst
                        + ", after the second="
                        + callsSecond))
        .flatMap(Optional::stream)
        .toList();
  }
}
