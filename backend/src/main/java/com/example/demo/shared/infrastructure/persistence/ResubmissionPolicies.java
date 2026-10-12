package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import com.example.demo.shared.events.ResubmissionPolicy;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;
import org.jooq.CaseValueStep;
import org.jooq.CaseWhenStep;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * イベント出版を、リスナーの {@code on} に付けた {@link ResubmissionPolicy} の方針に対応づける（ADR-077）。
 *
 * <p>出版はリスナーの ID（{@code listener_id}、{@link TargetEventPublication#getTargetIdentifier()}）で区別する。
 * Spring Modulith 2.1.1 は、{@code TransactionalApplicationListener.getListenerId()} を ID にし、Spring
 * Framework は、注釈の {@code id} が空ならメソッドの完全修飾名と引数の型（{@code <クラス>.<メソッド>(<型>,...)}）を ID にする。 起動のときに
 * bean のメソッドを走査して同じ ID を作る。
 */
@Slf4j
@Component
class ResubmissionPolicies {

  /** 方針の設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /** リスナーの ID から方針の名前。方針を指定しないリスナーは含まない。 */
  private final Map<String, String> policyNames;

  /** 方針を指定したリスナーの方針と既定の方針の、再投入の回数の上限を SQL で求める式。 */
  private final Field<Integer> maxResubmissionsExpression;

  /* package */ ResubmissionPolicies(
      final ConfigurableListableBeanFactory beanFactory,
      final EventPublicationResubmissionConfig.Properties properties) {
    this.properties = properties;
    this.policyNames = Map.copyOf(scan(beanFactory, properties));
    this.maxResubmissionsExpression = buildMaxResubmissions(policyNames, properties);
  }

  /** bean の {@link ResubmissionPolicy} を付けたメソッドを走査し、リスナーの ID と方針の名前を返す。設定にない名前があれば起動に失敗する。 */
  // 走査は構築の中の 1 スレッドで終わり、共有するのは Map.copyOf で不変にした後なので、並行の Map は要らない。
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static Map<String, String> scan(
      final ConfigurableListableBeanFactory beanFactory,
      final EventPublicationResubmissionConfig.Properties properties) {
    final Map<String, String> names = new HashMap<>();
    for (final String beanName : beanFactory.getBeanDefinitionNames()) {
      final @Nullable Class<?> type = targetClassOf(beanFactory, beanName);
      if (type == null) {
        continue;
      }
      final Map<Method, ResubmissionPolicy> methods =
          MethodIntrospector.selectMethods(
              type,
              (MethodIntrospector.MetadataLookup<ResubmissionPolicy>)
                  method ->
                      AnnotatedElementUtils.findMergedAnnotation(method, ResubmissionPolicy.class));
      methods.forEach(
          (method, policy) -> {
            if (!properties.policies().containsKey(policy.value())) {
              throw new IllegalStateException(
                  "@ResubmissionPolicy(\""
                      + policy.value()
                      + "\") on "
                      + method
                      + " is not defined in event-publication.resubmission.policies "
                      + properties.policies().keySet());
            }
            names.put(listenerId(method), policy.value());
          });
    }
    return names;
  }

  /**
   * bean の proxy の元のクラスを返す。型を解決できない bean は {@code null} を返して読み飛ばす。
   *
   * <p>{@code EventListenerMethodProcessor} も同じ呼び出しを囲んで読み飛ばす。 型を解決できない bean は
   * {@code @ApplicationModuleListener} を持てないため、再投入と無関係な bean 1 つで起動を落とさない。 {@code OutOfMemoryError}
   * などの {@code Error} は受けない。
   */
  // 遅延の bean や欠けたクラスで型の解決が RuntimeException か LinkageError になり、種類を絞れないため受ける。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static @Nullable Class<?> targetClassOf(
      final ConfigurableListableBeanFactory beanFactory, final String beanName) {
    try {
      // EventListenerMethodProcessor と同じく、proxy の元のクラスのメソッドを見る。
      return AutoProxyUtils.determineTargetClass(beanFactory, beanName);
    } catch (RuntimeException | LinkageError e) {
      log.atDebug()
          .addKeyValue("bean", beanName)
          .setCause(e)
          .log("Skipped a bean whose type cannot be resolved");
      return null;
    }
  }

  /** Spring Framework の {@code ApplicationListenerMethodAdapter} と同じ規則で、リスナーの ID を作る。 */
  /* package */ static String listenerId(final Method method) {
    final @Nullable EventListener listener =
        AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class);
    if (listener == null) {
      throw new IllegalStateException(
          "@ResubmissionPolicy must be placed on an @ApplicationModuleListener method: " + method);
    }
    if (!listener.id().isEmpty()) {
      return listener.id();
    }
    final StringJoiner parameters = new StringJoiner(",", "(", ")");
    for (final Class<?> type : method.getParameterTypes()) {
      parameters.add(type.getName());
    }
    return ClassUtils.getQualifiedMethodName(method) + parameters;
  }

  /** リスナーの ID の方針の名前。指定がなければ {@code default}。 */
  /* package */ String nameOf(final String listenerId) {
    return policyNames.getOrDefault(listenerId, EventPublicationResubmissionConfig.DEFAULT_POLICY);
  }

  /** リスナーの ID の方針。 */
  /* package */ EventPublicationResubmissionConfig.Policy policyOf(final String listenerId) {
    return Objects.requireNonNull(properties.policies().get(nameOf(listenerId)), "policy");
  }

  /** 出版のリスナーの ID。Spring Modulith 2.1.1 の再投入は {@link TargetEventPublication} を渡す。 */
  /* package */ static String listenerIdOf(final EventPublication publication) {
    return publication instanceof TargetEventPublication target
        ? target.getTargetIdentifier().getValue()
        : "";
  }

  /** 出版の方針の上限に達しておらず、最後の試行から次の待ち時間を過ぎたか。 */
  /* package */ boolean isDue(final EventPublication publication, final Instant now) {
    final EventPublicationResubmissionConfig.Policy policy = policyOf(listenerIdOf(publication));
    final int attempts = publication.getCompletionAttempts();
    return attempts <= policy.maxResubmissions()
        && lastAttempt(publication)
            .plus(policy.waitBefore(properties.waitAge(), attempts))
            .isBefore(now);
  }

  /** 最後の試行の時刻。再投入していなければ最初の試行の時刻。 */
  private static Instant lastAttempt(final EventPublication publication) {
    return Objects.requireNonNullElse(
        publication.getLastResubmissionDate(), publication.getPublicationDate());
  }

  /** {@code modulith.event_publication} の行の、リスナーの方針の再投入の回数の上限。 */
  /* package */ Field<Integer> maxResubmissions() {
    return maxResubmissionsExpression;
  }

  private static Field<Integer> buildMaxResubmissions(
      final Map<String, String> policyNames,
      final EventPublicationResubmissionConfig.Properties properties) {
    final Field<Integer> fallback =
        DSL.inline(
            Objects.requireNonNull(
                    properties.policies().get(EventPublicationResubmissionConfig.DEFAULT_POLICY),
                    "default policy")
                .maxResubmissions());
    if (policyNames.isEmpty()) {
      return fallback;
    }
    final CaseValueStep<String> byListener = DSL.choose(EVENT_PUBLICATION.LISTENER_ID);
    @Nullable CaseWhenStep<String, Integer> cases = null;
    for (final Map.Entry<String, String> entry : policyNames.entrySet()) {
      final Field<Integer> max =
          DSL.inline(
              Objects.requireNonNull(properties.policies().get(entry.getValue()), "policy")
                  .maxResubmissions());
      cases =
          cases == null
              ? byListener.when(DSL.inline(entry.getKey()), max)
              : cases.when(DSL.inline(entry.getKey()), max);
    }
    return Objects.requireNonNull(cases).otherwise(fallback);
  }
}
