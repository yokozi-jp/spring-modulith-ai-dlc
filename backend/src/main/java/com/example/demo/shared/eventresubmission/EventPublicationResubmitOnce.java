package com.example.demo.shared.eventresubmission;

import com.example.demo.shared.eventresubmission.EventPublicationStates.State;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.modulith.events.EventPublication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 失敗したイベント出版を、運用者の指定で 1 回だけ再投入し、リスナーの処理が終わるまで待つ（ADR-079）。
 *
 * <p>原因を直したあとに、自動の再投入を待たずに手で再投入する入口で使う。待ち時間は見ず、対象に合う {@code FAILED} の出版を {@code max-per-run}
 * 件まで再投入する。回数の上限を超えて {@code ABANDONED} になった出版は、Spring Modulith 2.2.0 に {@code FAILED} へ戻す API
 * がないため、Runbook の SQL で戻してから使う（ADR-079）。定期の再投入と同じロックを取り、取れるまで {@code lock-at-most-for} を上限に待つ。
 * ロックの中では、定期の再投入と同じく、対象によらず古い出版を {@code FAILED} に戻してから再投入する（{@link FailedPublicationSweep}）。
 * リスナーは別のスレッドで動くため、再投入した出版が {@code PUBLISHED}、{@code PROCESSING}、{@code RESUBMITTED}
 * でなくなるまで待ち、件数を記録する。
 *
 * <p>定期の再投入にある {@code lock-at-most-for} の半分の時間予算は適用しない。この入口は完了を待つ設計で、{@code max-per-run} だけで件数を抑える。
 */
// 失敗した出版を手で再投入する運用者の入口で、docs/backend/class-roles/index.md にない役割である。
// 結合テストが他のモジュールのリスナーの回復を確かめるため public にする。他のモジュールの本番のコードからは ArchUnit が使わせない。
@Slf4j
@Component
public class EventPublicationResubmitOnce {

  /** すべての FAILED の出版を対象にする指定。 */
  private static final String ALL = "all";

  /** 方針の名前で対象を選ぶ指定の接頭辞。 */
  private static final String POLICY_PREFIX = "policy:";

  /** イベントの完全修飾名で対象を選ぶ指定の接頭辞。 */
  private static final String EVENT_TYPE_PREFIX = "event-type:";

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** ロックを取って、失敗した出版を再投入する。 */
  private final FailedPublicationSweep sweep;

  /** リスナーの再投入の方針。 */
  private final ResubmissionPolicies policies;

  /** 定期の再投入の設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /** 手での 1 回の再投入の設定値。 */
  private final EventPublicationResubmissionConfig.ResubmitOnceProperties onceProperties;

  /* package */ EventPublicationResubmitOnce(
      final DSLContext dsl,
      final FailedPublicationSweep sweep,
      final ResubmissionPolicies policies,
      final EventPublicationResubmissionConfig.Properties properties,
      final EventPublicationResubmissionConfig.ResubmitOnceProperties onceProperties) {
    this.dsl = dsl;
    this.sweep = sweep;
    this.policies = policies;
    this.properties = properties;
    this.onceProperties = onceProperties;
  }

  /**
   * 再投入の対象。環境変数の文字列は {@link #parse} で 1 回だけ型にする。
   *
   * <p>{@link Policy} の名前が設定にあるかは、{@link EventPublicationResubmitOnce#run} が対象を選ぶときに確かめる。
   */
  public sealed interface Target {

    /** すべての {@code FAILED} の出版。 */
    record All() implements Target {}

    /** 方針の名前で選ぶ出版。 */
    record Policy(String name) implements Target {}

    /** イベントの完全修飾名で選ぶ出版。 */
    record EventType(String className) implements Target {}

    /**
     * {@code all}、{@code policy:<方針の名前>}、{@code event-type:<イベントの完全修飾名>} のいずれかを読む。
     *
     * @throws IllegalArgumentException どれにも合わないとき
     */
    static Target parse(final String raw) {
      if (ALL.equals(raw)) {
        return new All();
      }
      if (raw.startsWith(POLICY_PREFIX) && raw.length() > POLICY_PREFIX.length()) {
        return new Policy(raw.substring(POLICY_PREFIX.length()));
      }
      if (raw.startsWith(EVENT_TYPE_PREFIX) && raw.length() > EVENT_TYPE_PREFIX.length()) {
        return new EventType(raw.substring(EVENT_TYPE_PREFIX.length()));
      }
      throw new IllegalArgumentException(
          "event-publication.resubmit-once.target must be all, policy:<name> or event-type:<class>: "
              + raw);
    }
  }

  /**
   * 対象の FAILED の出版を再投入し、リスナーの処理が終わるか {@code completion-timeout} を過ぎるまで待つ。
   *
   * @param target 再投入する対象
   * @return 再投入した件数と、その処理の結果
   * @throws IllegalArgumentException 方針の名前が設定にないとき
   * @throws IllegalStateException トランザクションの中で呼んだとき、{@code lock-at-most-for} の間にロックを取れなかったとき
   */
  public Outcome run(final Target target) {
    // トランザクションの中で呼ぶと、再投入の印がコミットされず、別のスレッドのリスナーが進めないまま待ち続ける。
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("resubmit-once must not run inside a transaction");
    }
    final FailedPublicationSweep.Swept swept = sweep.waitUpTo(selector(target));
    final Outcome outcome = awaitCompletion(swept.advanced().keySet(), swept.remaining());
    record(outcome);
    return outcome;
  }

  private Predicate<EventPublication> selector(final Target target) {
    return switch (target) {
      case Target.All _ -> publication -> true;
      case Target.Policy policy -> {
        requireKnownPolicy(policy);
        yield publication ->
            policy.name().equals(policies.nameOf(ResubmissionPolicies.listenerIdOf(publication)));
      }
      case Target.EventType type ->
          publication -> type.className().equals(publication.getEvent().getClass().getName());
    };
  }

  private void requireKnownPolicy(final Target.Policy policy) {
    if (!properties.policies().containsKey(policy.name())) {
      throw new IllegalArgumentException(
          "unknown resubmission policy: " + policy.name() + " " + properties.policies().keySet());
    }
  }

  /** 結果の件数を記録する。すべて完了したら INFO、それ以外は WARN にする。 */
  private static void record(final Outcome result) {
    log.atLevel(result.succeeded() ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN)
        .addKeyValue("event_publication.resubmitted.count", result.resubmitted())
        .addKeyValue("event_publication.completed.count", result.completed())
        .addKeyValue("event_publication.incomplete.count", result.incomplete())
        .addKeyValue("event_publication.remaining.count", result.remaining())
        .log(
            result.succeeded()
                ? "Event publications resubmitted once"
                : "Event publication resubmit-once incomplete");
  }

  /**
   * 再投入した出版の処理が終わるまで待ち、結果を数える。完了した出版は archive の {@code COMPLETED} の行で数える。
   *
   * <p>回数の上限を超えて失敗した出版は、{@code ABANDONED} で archive へ移り、完了と数えない。
   */
  private Outcome awaitCompletion(final Set<UUID> ids, final int remaining) {
    final int polls = Polling.pollsWithin(onceProperties.completionTimeout());
    for (int poll = 0; poll < polls && count(ids, State::isInFlight) > 0; poll++) {
      Polling.sleep();
    }
    final int completed = count(ids, state -> state.status() == EventPublication.Status.COMPLETED);
    return new Outcome(ids.size(), completed, ids.size() - completed, remaining);
  }

  /** 出版のうち、状態が条件に合う数。 */
  private int count(final Collection<UUID> ids, final Predicate<State> condition) {
    return (int) EventPublicationStates.read(dsl, ids).values().stream().filter(condition).count();
  }

  /**
   * 1 回の再投入の結果。
   *
   * @param resubmitted 再投入して {@code RESUBMITTED} へ進んだ出版の数。選ばれても進まなかった出版は数えない
   * @param completed そのうち処理を完了した数
   * @param incomplete そのうち FAILED に戻るか ABANDONED になった数と、待つ上限を過ぎても処理中の数
   * @param remaining 対象に合うが、{@code max-per-run} を超えたため再投入しなかった数
   */
  public record Outcome(int resubmitted, int completed, int incomplete, int remaining) {

    /** 再投入した出版がすべて完了し、残りもないか。 */
    public boolean succeeded() {
      return incomplete == 0 && remaining == 0;
    }
  }
}
