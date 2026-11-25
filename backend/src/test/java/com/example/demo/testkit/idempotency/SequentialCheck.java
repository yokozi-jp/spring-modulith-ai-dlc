package com.example.demo.testkit.idempotency;

import static com.example.demo.testkit.idempotency.PublicationRegistry.COMPLETION_TIMEOUT;

import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.idempotency.ExternalCallRecorder.ExternalCall;
import com.example.demo.testkit.idempotency.PublicationRegistry.Publications;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.springframework.core.env.Environment;

/**
 * 順の 2 回の検査。前提を 2 つのテナントに作り、{@link AsTenant#OTHER_TENANT} のイベントを順に 2 回届け、両方のテナントの行と外部システムの呼び出しを比べる。
 */
/* package */ final class SequentialCheck {

  /** テナントを登録する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 出版と、出版の状態の読み取り。 */
  private final PublicationRegistry registry;

  /** 外部システムの呼び出しの記録。 */
  private final ExternalCallRecorder calls;

  /** 業務の表を所有者の接続で読む設定。 */
  private final Environment environment;

  /* package */ SequentialCheck(
      final DSLContext dsl,
      final PublicationRegistry registry,
      final ExternalCallRecorder calls,
      final Environment environment) {
    this.dsl = dsl;
    this.registry = registry;
    this.calls = calls;
    this.environment = environment;
  }

  /**
   * 前提を作り、同じ本文のイベントを順に 2 回届けて比べる。
   *
   * <p>前提を作る 2 つのテナントを {@code m_tenant} に登録する。業務の表は {@code m_tenant} のテナントごとに読むためである。
   */
  /* package */ List<Violation> run(final ListenerExample example) {
    Stream.of(AsTenant.TENANT, AsTenant.OTHER_TENANT)
        .forEach(tenant -> TestTenancy.member(dsl, AppUsers.USER_ID, tenant.value(), "MEMBER"));
    final ListenerId listener = example.listener();
    example.prepare(AsTenant.TENANT);
    final Object event = example.prepare(AsTenant.OTHER_TENANT);
    calls.reset();
    final TableRows before = TableRows.read(environment);
    final Publications start = registry.read(listener);

    registry.publish(event);
    final Publications first =
        registry.await(listener, COMPLETION_TIMEOUT, state -> state.settledSince(start) > 0);
    final TableRows afterFirst = TableRows.read(environment);
    final Set<ExternalCall> callsFirst = calls.distinct();
    final Optional<Violation> firstUnsettled =
        first
            .unsettledSince(start, 1)
            .map(kind ->
                new Violation(
                    listener, kind, "first delivery: event=" + event + first.describeSince(start)));
    if (firstUnsettled.isPresent()) {
      return firstUnsettled.stream().toList();
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
            second
                .unsettledSince(first, 1)
                .map(kind ->
                    new Violation(
                        listener,
                        kind,
                        "second delivery: event=" + event + second.describeSince(first))),
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
