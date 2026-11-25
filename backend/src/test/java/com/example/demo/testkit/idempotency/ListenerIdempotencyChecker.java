package com.example.demo.testkit.idempotency;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.idempotency.ExternalCallRecorder.ExternalCall;
import com.example.demo.testkit.idempotency.Violation.Kind;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.test.Scenario;

/**
 * すべての {@code @ApplicationModuleListener} が、同じイベントの 2 回目（順と同時）で業務の表の行と外部システムの呼び出しを変えないことを確かめる。
 *
 * <p>違反は例外にせず一覧で返す。全件のテストは一覧が空であることを確かめ、自己検査は違反の種類を確かめる。
 *
 * <p>順の 2 回は、2 つのテナントに前提を作り、既定でないほうのテナント（{@link #OTHER_TENANT}）のイベントを 2 回届け、両方のテナントの行を比べる。同時の 2
 * 回は、1 回目をコミットの直前の {@link CommitGate} で止め、2 回目がロックを待つか門に届くまで待ってから 1 回目を通す。1 回目だけがコミットされた時点の中身を「1
 * 回だけ届けたとき」とし、2 回目のコミットの後と比べる。2 回目がロックを待つのを待つ上限は、接続の {@code lock_timeout}（{@code
 * DB_LOCK_TIMEOUT_MS}）の半分にする。
 */
// 検査の手順を 1 か所に集めるため、結合する型とメソッドが多い。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
public final class ListenerIdempotencyChecker {

  /** 既定でないほうのテナント。順の 2 回で、テナントを設定せずに読まないことを確かめるために使う。 */
  public static final TenantId OTHER_TENANT =
      new TenantId(UUID.fromString("9c1f6a2e-3b4d-4e5f-8a70-1b2c3d4e5f60"));

  /** 同時の 2 回で届ける配信の数。 */
  private static final int TWO = 2;

  /** 出版の完了を待つ上限。{@link CommitGate#HOLD_LIMIT} 以上にする。 */
  private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(10);

  /** 状態を読み直す間隔。ロックの待ちを {@code lock_timeout} より十分に早く見つけるため短くする。 */
  private static final Duration POLL_INTERVAL = Duration.ofMillis(10);

  /** 違反の説明の、出版の状態の欄。 */
  private static final String REGISTRY_LABEL = ", registry=";

  /** 失敗した出版の状態。 */
  private static final String FAILED = "FAILED";

  /** すべての Bean を走査する。 */
  private final ConfigurableListableBeanFactory beanFactory;

  /** モジュールの入力例。 */
  private final List<ListenerExample> examples;

  /** 出版の状態とロックを読み、テナントを登録する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 所有者の接続と {@code lock_timeout} を読む設定。 */
  private final Environment environment;

  /** コミットの直前の門。 */
  private final CommitGate gate;

  /** 外部システムの呼び出しの記録。 */
  private final ExternalCallRecorder calls;

  /** イベントを出版のレジストリと同じ形で直列化する。 */
  private final EventSerializer serializer;

  /* package */ ListenerIdempotencyChecker(
      final ConfigurableListableBeanFactory beanFactory,
      final List<ListenerExample> examples,
      final DSLContext dsl,
      final Environment environment,
      final CommitGate gate,
      final ExternalCallRecorder calls,
      final EventSerializer serializer) {
    this.beanFactory = beanFactory;
    this.examples = List.copyOf(examples);
    this.dsl = dsl;
    this.environment = environment;
    this.gate = gate;
    this.calls = calls;
    this.serializer = serializer;
  }

  /** 文脈のすべての Bean から、{@code @ApplicationModuleListener} のメソッドのリスナーの ID を集める。 */
  public List<ListenerId> allListeners() {
    return Arrays.stream(beanFactory.getBeanDefinitionNames())
        .map(beanName -> AutoProxyUtils.determineTargetClass(beanFactory, beanName))
        .filter(Objects::nonNull)
        .flatMap(type -> ListenerId.listenerMethods(type).stream())
        .map(ListenerId::forMethod)
        .distinct()
        .sorted(Comparator.comparing(ListenerId::value))
        .toList();
  }

  /** 入力例のないリスナーと、リスナーのない入力例を返す。 */
  public List<Violation> checkCoverage() {
    final List<ListenerId> listeners = allListeners();
    final Set<ListenerId> covered =
        examples.stream().map(ListenerExample::listener).collect(Collectors.toSet());
    final Stream<Violation> uncovered =
        listeners.stream()
            .filter(listener -> !covered.contains(listener))
            .map(listener ->
                new Violation(
                    listener,
                    Kind.NO_EXAMPLE,
                    "the listener has no ListenerExample bean."
                        + " Add one to the module's tests and @Import it into"
                        + " ListenerIdempotencyTest"));
    final Stream<Violation> orphans =
        examples.stream()
            .filter(example -> !listeners.contains(example.listener()))
            .map(example ->
                new Violation(
                    example.listener(),
                    Kind.ORPHAN_EXAMPLE,
                    example.getClass().getName() + " names a listener that is not in the context"));
    return Stream.concat(uncovered, orphans).toList();
  }

  /** 前提を 2 つのテナントに作り、既定でないほうのテナントのイベントを順に 2 回届けて比べる。 */
  public List<Violation> checkSequential(final Scenario scenario, final ListenerExample example) {
    final ListenerId listener = example.listener();
    ensureTenants();
    example.prepare(AsTenant.TENANT);
    final Object event = example.prepare(OTHER_TENANT);
    calls.reset();
    final TableRows before = TableRows.read(environment);

    final Registry start = registry(listener);
    final Registry first = deliver(scenario, listener, event, start);
    final TableRows afterFirst = TableRows.read(environment);
    final Set<ExternalCall> callsFirst = calls.distinct();
    if (!first.completedOnceSince(start)) {
      return List.of(
          new Violation(
              listener,
              Kind.PUBLICATION_NOT_COMPLETED,
              "first delivery: event=" + event + REGISTRY_LABEL + first.since(start)));
    }
    final Registry second = deliver(scenario, listener, event, first);
    final TableRows afterSecond = TableRows.read(environment);
    final Set<ExternalCall> callsSecond = calls.distinct();
    final String diff = afterFirst.diff(afterSecond);
    return Stream.of(
            violationIf(
                before.equals(afterFirst),
                listener,
                Kind.FIRST_DELIVERY_CHANGED_NOTHING,
                "event=" + event + " changed no row in any tenant"),
            violationIf(
                !second.completedOnceSince(first),
                listener,
                Kind.PUBLICATION_NOT_COMPLETED,
                "second delivery: event=" + event + REGISTRY_LABEL + second.since(first)),
            violationIf(
                !diff.isEmpty(),
                listener,
                Kind.SECOND_DELIVERY_CHANGED_ROWS,
                "event=" + event + " changed rows on the second delivery:" + diff),
            violationIf(
                !callsFirst.equals(callsSecond),
                listener,
                Kind.EXTERNAL_CALLS_DIFFER,
                "sequential: after the first delivery="
                    + callsFirst
                    + ", after the second="
                    + callsSecond))
        .flatMap(Optional::stream)
        .toList();
  }

  /**
   * 前提を作り、1 回目をコミットの直前で止めたまま 2 回目を届けて競合させ、1 回だけ届けたときと比べる。
   *
   * <p>止めた配信は、戻る前に必ず通す。
   */
  public List<Violation> checkConcurrent(final Scenario scenario, final ListenerExample example) {
    final ListenerId listener = example.listener();
    ensureTenants();
    final Object event = example.prepare(AsTenant.TENANT);
    calls.reset();
    final CommitGate.Hold hold = gate.close(listener);
    try {
      return new Race(scenario, listener, event, hold).run();
    } finally {
      hold.open();
    }
  }

  /** 同時の 2 回の 1 回分の手順。 */
  private final class Race {

    /** 出版に使う Scenario。 */
    private final Scenario scenario;

    /** 対象のリスナー。 */
    private final ListenerId listener;

    /** 届けるイベント。 */
    private final Object event;

    /** 閉じた門。 */
    private final CommitGate.Hold hold;

    /** 1 回目を届ける前の出版の数。 */
    private final Registry start;

    private Race(
        final Scenario scenario,
        final ListenerId listener,
        final Object event,
        final CommitGate.Hold hold) {
      this.scenario = scenario;
      this.listener = listener;
      this.event = event;
      this.hold = hold;
      this.start = registry(listener);
    }

    private List<Violation> run() {
      final Optional<Violation> notStopped = stopFirstBeforeCommit();
      if (notStopped.isPresent()) {
        return List.of(notStopped.get());
      }
      final Set<ExternalCall> once = calls.distinct();
      final Optional<Violation> notContended = contendSecond();
      if (notContended.isPresent()) {
        return List.of(notContended.get());
      }
      return commitBothAndCompare(once);
    }

    /** 1 回目を、コミットの直前の門に届くまで流す。 */
    private Optional<Violation> stopFirstBeforeCommit() {
      final Registry state =
          publishAndWait(
              scenario,
              event,
              listener,
              COMPLETION_TIMEOUT,
              current -> hold.arrivals() > 0 || current.settledSince(start) > 0);
      return violationIf(
          hold.arrivals() == 0,
          listener,
          Kind.GATE_NOT_REACHED,
          "the first delivery did not stop before its commit: outsideTransaction="
              + hold.outsideTransaction()
              + ", failures="
              + hold.failures()
              + REGISTRY_LABEL
              + state.since(start));
    }

    /** 2 回目を、1 回目のロックを待つか、門に届くか、失敗するまで流す。 */
    private Optional<Violation> contendSecond() {
      final Duration limit = lockWaitLimit();
      final Registry state =
          publishAndWait(scenario, event, listener, limit, current -> contended(current));
      return violationIf(
          !contended(state),
          listener,
          Kind.GATE_NOT_REACHED,
          "the second delivery neither waited for a lock nor reached the commit within "
              + limit
              + ": registry="
              + state.since(start));
    }

    private boolean contended(final Registry state) {
      return lockWaiters() > 0 || hold.arrivals() >= TWO || state.failed > start.failed;
    }

    /** 1 回目をコミットさせて中身を読み、2 回目をコミットさせて比べる。 */
    private List<Violation> commitBothAndCompare(final Set<ExternalCall> once) {
      final String race =
          "secondDelivery=" + (lockWaiters() > 0 ? "waiting for a lock" : "at the gate or failed");
      hold.releaseNext();
      final Registry afterFirst =
          awaitRegistry(
              listener,
              "the first commit of " + listener,
              state ->
                  state.archived > start.archived
                      && (hold.arrivals() >= TWO || state.failed > start.failed));
      if (afterFirst.failed > start.failed || afterFirst.archived == start.archived) {
        return List.of(
            new Violation(
                listener,
                Kind.CONCURRENT_DELIVERY_FAILED,
                "a delivery failed after the first committed ("
                    + race
                    + "): failures="
                    + hold.failures()
                    + REGISTRY_LABEL
                    + afterFirst.since(start)));
      }
      final TableRows single = TableRows.read(environment);
      hold.releaseNext();
      final Registry end =
          awaitRegistry(
              listener,
              "the second commit of " + listener,
              state -> state.settledSince(start) >= TWO);
      final String diff = single.diff(TableRows.read(environment));
      final Set<ExternalCall> twice = calls.distinct();
      return Stream.of(
              violationIf(
                  end.archived - start.archived != TWO,
                  listener,
                  Kind.CONCURRENT_DELIVERY_FAILED,
                  "the second delivery did not complete ("
                      + race
                      + "): failures="
                      + hold.failures()
                      + REGISTRY_LABEL
                      + end.since(start)),
              violationIf(
                  !diff.isEmpty(),
                  listener,
                  Kind.CONCURRENT_RESULT_DIFFERS,
                  "event="
                      + event
                      + " changed rows after the first delivery had committed ("
                      + race
                      + "):"
                      + diff),
              violationIf(
                  !once.equals(twice),
                  listener,
                  Kind.EXTERNAL_CALLS_DIFFER,
                  "concurrent: one delivery=" + once + ", two deliveries=" + twice))
          .flatMap(Optional::stream)
          .toList();
    }
  }

  private static Optional<Violation> violationIf(
      final boolean violated, final ListenerId listener, final Kind kind, final String detail) {
    return violated ? Optional.of(new Violation(listener, kind, detail)) : Optional.empty();
  }

  /** イベントを 1 回出版し、{@code start} の後の完了か失敗を待つ。 */
  private Registry deliver(
      final Scenario scenario,
      final ListenerId listener,
      final Object event,
      final Registry start) {
    return publishAndWait(
        scenario, event, listener, COMPLETION_TIMEOUT, state -> state.settledSince(start) > 0);
  }

  /**
   * イベントの同じ本文の別のインスタンスを Scenario で出版し、出版の状態が条件を満たすか上限を過ぎるまで待ち、最後に読んだ状態を返す。
   *
   * <p>別のインスタンスにするのは、Spring Modulith 2.2.0 が処理中の出版をイベントのインスタンスの同一性とリスナーで引くためである。同じインスタンスを同時に 2
   * 回出版すると、1 回目の完了が 2 回目の記録を消し、2 回目の失敗が {@code FAILED} にならない。本番の重複（再投入、発行元の再発行）は別のインスタンスである。
   */
  private Registry publishAndWait(
      final Scenario scenario,
      final Object event,
      final ListenerId listener,
      final Duration timeout,
      final Predicate<Registry> done) {
    final Object copy = serializer.deserialize(serializer.serialize(event), event.getClass());
    try {
      scenario
          .publish(copy)
          .customize(it -> it.atMost(timeout).pollInterval(POLL_INTERVAL))
          .andWaitForStateChange(() -> registry(listener), done)
          .andVerify(state -> {});
      return registry(listener);
    } catch (ConditionTimeoutException e) {
      // 上限を過ぎたら、最後の状態を返して呼び出し側が違反にする。
      return registry(listener);
    }
  }

  private Registry awaitRegistry(
      final ListenerId listener, final String alias, final Predicate<Registry> done) {
    try {
      return Awaitility.await(alias)
          .atMost(COMPLETION_TIMEOUT)
          .pollInterval(POLL_INTERVAL)
          .until(() -> registry(listener), done);
    } catch (ConditionTimeoutException e) {
      // 上限を過ぎたら、最後の状態を返して呼び出し側が違反にする。
      return registry(listener);
    }
  }

  /** 2 回目がロックを待つのを待つ上限。接続の {@code lock_timeout} の半分にする。 */
  private Duration lockWaitLimit() {
    return Duration.ofMillis(environment.getRequiredProperty("DB_LOCK_TIMEOUT_MS", Long.class) / 2);
  }

  /** 与えられていないロックを待つセッションの数。テストの DB は JVM ごとに分かれ、ほかのテストのロックは混ざらない。 */
  private int lockWaiters() {
    return dsl.fetchCount(
        DSL.table(DSL.name("pg_catalog", "pg_locks")), DSL.field(DSL.name("granted")).isFalse());
  }

  /** 既定のテナントと既定でないほうのテナントを {@code m_tenant} に登録する。業務の表は {@code m_tenant} のテナントごとに読むためである。 */
  private void ensureTenants() {
    Stream.of(AsTenant.TENANT, OTHER_TENANT)
        .forEach(tenant -> TestTenancy.member(dsl, AppUsers.USER_ID, tenant.value(), "MEMBER"));
  }

  /** リスナーの出版の数。 */
  private Registry registry(final ListenerId listener) {
    final Map<String, Integer> incomplete =
        dsl.select(EVENT_PUBLICATION.STATUS, DSL.count())
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.LISTENER_ID.eq(listener.value()))
            .groupBy(EVENT_PUBLICATION.STATUS)
            .fetchMap(r -> String.valueOf(r.value1()), r -> r.value2());
    final int archived =
        dsl.fetchCount(
            EVENT_PUBLICATION_ARCHIVE, EVENT_PUBLICATION_ARCHIVE.LISTENER_ID.eq(listener.value()));
    return new Registry(archived, incomplete.getOrDefault(FAILED, 0), incomplete);
  }

  /**
   * リスナーの出版の数。
   *
   * @param archived 完了した出版の数
   * @param failed {@code FAILED} の出版の数
   * @param incomplete 未完了の出版の状態ごとの数
   */
  private record Registry(int archived, int failed, Map<String, Integer> incomplete) {

    /** {@code start} の後に完了したか失敗した出版の数。 */
    /* package */ int settledSince(final Registry start) {
      return archived - start.archived + failed - start.failed;
    }

    /** {@code start} の後に、出版がちょうど 1 つ完了し、失敗がないか。 */
    /* package */ boolean completedOnceSince(final Registry start) {
      return archived == start.archived + 1 && failed == start.failed;
    }

    /** {@code start} からの変化の説明。 */
    /* package */ String since(final Registry start) {
      return "archived+"
          + (archived - start.archived)
          + ", failed+"
          + (failed - start.failed)
          + ", incomplete="
          + incomplete;
    }
  }

  /** 全件のテストと自己検査が {@code @Import} する、検査と門と呼び出しの記録の構成。 */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    /** 外部システムの呼び出しの記録。ほかの Bean の後処理に使うため static にする。 */
    @Bean
    /* package */ static ExternalCallRecorder externalCallRecorder() {
      return new ExternalCallRecorder();
    }

    /** コミットの直前の門。 */
    @Bean
    /* package */ CommitGate commitGate() {
      return new CommitGate();
    }

    /** 検査。 */
    @Bean
    /* package */ ListenerIdempotencyChecker listenerIdempotencyChecker(
        final ConfigurableListableBeanFactory beanFactory,
        final ObjectProvider<ListenerExample> examples,
        final DSLContext dsl,
        final Environment environment,
        final CommitGate gate,
        final ExternalCallRecorder calls,
        final EventSerializer serializer) {
      return new ListenerIdempotencyChecker(
          beanFactory,
          examples.orderedStream().toList(),
          dsl,
          environment,
          gate,
          calls,
          serializer);
    }
  }
}
