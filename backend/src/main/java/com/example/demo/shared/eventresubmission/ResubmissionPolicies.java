package com.example.demo.shared.eventresubmission;

import com.example.demo.shared.events.ListenerIds;
import com.example.demo.shared.events.ResubmissionPolicy;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.AbandonPolicy;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.stereotype.Component;

/**
 * イベント出版を、リスナーの {@code on} に付けた {@link ResubmissionPolicy} の方針に対応づけ、再投入してよいかを決める（ADR-079）。
 *
 * <p>出版はリスナーの ID（{@code listener_id}、{@link TargetEventPublication#getTargetIdentifier()}）で区別する。
 * Spring Modulith 2.2.0 は、{@code TransactionalApplicationListener.getListenerId()} を ID にし、Spring
 * Framework は、注釈の {@code id} が空ならメソッドの完全修飾名と引数の型（{@code <クラス>.<メソッド>(<型>,...)}）を ID にする。 起動のときに
 * bean のメソッドを走査し、{@link ListenerIds} で同じ ID を作る。
 *
 * <p>Spring Modulith の {@link AbandonPolicy} も実装し、方針の {@code abandon-after-attempts}
 * に達した出版を、全体の上限を待たずに {@code ABANDONED} にする。 冪等でない外部システムを呼ぶリスナーを、自動で再投入させないためである。
 */
@Slf4j
@Component
class ResubmissionPolicies implements AbandonPolicy {

  /** 方針の設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /** リスナーの ID から方針の名前。方針を指定しないリスナーは含まない。 */
  private final Map<String, String> policyNames;

  /** 方針の名前から、その方針の回数の上限。方針に指定がなければ全体の値。 */
  private final Map<String, ResubmissionLimit> limits;

  /** 方針を指定しないリスナーの回数の上限（全体の値）。 */
  private final ResubmissionLimit global;

  /* package */ ResubmissionPolicies(
      final ConfigurableListableBeanFactory beanFactory,
      final EventPublicationResubmissionConfig.Properties properties,
      final EventPublicationResubmissionConfig.AbandonProperties abandon) {
    this.properties = properties;
    this.global = new ResubmissionLimit(abandon.abandonAfterAttempts());
    this.limits = Map.copyOf(limitsByPolicy(properties, global));
    this.policyNames = Map.copyOf(scan(beanFactory, properties));
  }

  /**
   * 方針の名前ごとの回数の上限を返す。
   *
   * <p>方針の値が全体の値以上なら起動に失敗させる。同じ値は書く意味がなく、大きい値は定期の再投入が全体の上限を超えて再投入するためである。
   */
  // 構築の中の 1 スレッドで作り、Map.copyOf で不変にしてから共有するため、並行の Map は要らない。
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static Map<String, ResubmissionLimit> limitsByPolicy(
      final EventPublicationResubmissionConfig.Properties properties,
      final ResubmissionLimit global) {
    final Map<String, ResubmissionLimit> limits = new HashMap<>();
    properties
        .policies()
        .forEach((name, policy) -> {
          final @Nullable Integer own = policy.abandonAfterAttempts();
          if (own != null && own >= global.abandonAfterAttempts()) {
            throw new IllegalStateException(
                "event-publication.resubmission.policies."
                    + name
                    + ".abandon-after-attempts ("
                    + own
                    + ") must be less than spring.modulith.events.resubmission.abandon-after-attempts ("
                    + global.abandonAfterAttempts()
                    + ")");
          }
          limits.put(name, own != null ? new ResubmissionLimit(own) : global);
        });
    return limits;
  }

  /** bean の {@link ResubmissionPolicy} を付けたメソッドを走査し、リスナーの ID と方針の名前を返す。設定にない名前があれば起動に失敗する。 */
  // 走査は構築の中の 1 スレッドで終わり、共有するのは Map.copyOf で不変にした後なので、並行の Map は要らない。
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static Map<String, String> scan(
      final ConfigurableListableBeanFactory beanFactory,
      final EventPublicationResubmissionConfig.Properties properties) {
    final Map<String, String> names = new HashMap<>();
    for (final Class<?> type : ListenerIds.beanClasses(beanFactory).toList()) {
      final Map<Method, ResubmissionPolicy> methods =
          MethodIntrospector.selectMethods(
              type,
              (MethodIntrospector.MetadataLookup<ResubmissionPolicy>)
                  method ->
                      AnnotatedElementUtils.findMergedAnnotation(method, ResubmissionPolicy.class));
      methods.forEach((method, policy) -> {
        if (!properties.policies().containsKey(policy.value())) {
          throw new IllegalStateException(
              "@ResubmissionPolicy(\""
                  + policy.value()
                  + "\") on "
                  + method
                  + " is not defined in event-publication.resubmission.policies "
                  + properties.policies().keySet());
        }
        if (!AnnotatedElementUtils.hasAnnotation(method, EventListener.class)) {
          throw new IllegalStateException(
              "@ResubmissionPolicy must be placed on an @ApplicationModuleListener method: "
                  + method);
        }
        names.put(ListenerIds.fromMethod(method), policy.value());
      });
    }
    return names;
  }

  /** リスナーの ID の方針の名前。指定がなければ {@code default}。 */
  /* package */ String nameOf(final String listenerId) {
    return policyNames.getOrDefault(listenerId, EventPublicationResubmissionConfig.DEFAULT_POLICY);
  }

  /** リスナーの ID の回数の上限。方針に指定がなければ全体の値。 */
  /* package */ ResubmissionLimit limitOf(final String listenerId) {
    return Objects.requireNonNull(limits.get(nameOf(listenerId)), "limit");
  }

  /** 自動で再投入する回数の上限を、全体の値と違うリスナーだけ並べて返す。Gauge が行のリスナーごとに上限を比べるために使う。 */
  /* package */ MaxResubmissions maxResubmissionsByListener() {
    final Map<String, Integer> custom = new ConcurrentHashMap<>();
    policyNames.forEach((listenerId, name) -> {
      final int max = limitOf(listenerId).maxResubmissions();
      if (max != global.maxResubmissions()) {
        custom.put(listenerId, max);
      }
    });
    return new MaxResubmissions(Map.copyOf(custom), global.maxResubmissions());
  }

  /**
   * リスナーの方針の {@code abandon-after-attempts} に達した出版を諦める。
   *
   * <p>渡る出版の {@code getCompletionAttempts()} は再投入の前に読んだ値で（ADR-079 の Context の表）、Spring Modulith
   * の既定と同じく {@code >=} で比べる。 最初の配信で諦めた出版は、定期のジョブが再投入しておらず {@code exhausted} の ERROR を出さないため、ここで
   * ERROR を出す。
   */
  @Override
  public Decision shouldAbandon(final EventPublication publication) {
    final String listenerId = listenerIdOf(publication);
    if (!limitOf(listenerId).abandons(publication.getCompletionAttempts())) {
      return Decision.DEFAULT;
    }
    // 最初の配信は作ったときの PUBLISHED の値、再投入は読んだ FAILED の行の値が渡る（ADR-079 の Context の表）。
    if (publication.getStatus() == EventPublication.Status.PUBLISHED) {
      log.atError()
          .addKeyValue("event_publication.id", publication.getIdentifier().toString())
          .addKeyValue("event_publication.event_type", publication.getEvent().getClass().getName())
          .addKeyValue("event_publication.listener_id", listenerId)
          .addKeyValue("event_publication.resubmission_policy", nameOf(listenerId))
          .log("Event publication abandoned on first delivery");
    }
    return Decision.ABANDON;
  }

  /** 出版のリスナーの ID。Spring Modulith 2.2.0 の再投入は {@link TargetEventPublication} を渡す。 */
  /* package */ static String listenerIdOf(final EventPublication publication) {
    return publication instanceof TargetEventPublication target
        ? target.getTargetIdentifier().getValue()
        : "";
  }

  /**
   * 出版の再投入の回数がリスナーの上限に達しておらず、最後の試行から次の待ち時間を過ぎたか。
   *
   * <p>上限の回数目の再投入で失敗した出版は Spring Modulith が {@code ABANDONED} にするため、ここに来ない。 回数を見るのは、失敗を記録せずに上限を超えて
   * {@code FAILED} に戻った出版（最後の試行の途中で落ち、古い出版として FAILED に戻った出版など）を、上限を超えて再投入しないためである（ADR-079）。
   */
  /* package */ boolean isDue(final EventPublication publication, final Instant now) {
    final int attempts = publication.getCompletionAttempts();
    final String listenerId = listenerIdOf(publication);
    return attempts <= limitOf(listenerId).maxResubmissions()
        && publication
            .getLastPublicationDate()
            .plus(
                Objects.requireNonNull(properties.policies().get(nameOf(listenerId)), "policy")
                    .waitBefore(properties.waitAge(), attempts))
            .isBefore(now);
  }

  /**
   * リスナーごとの、自動で再投入する回数の上限。
   *
   * @param byListener 全体の値と違うリスナーの ID と、その上限
   * @param otherwise それ以外のリスナーの上限
   */
  /* package */ record MaxResubmissions(Map<String, Integer> byListener, int otherwise) {}
}
