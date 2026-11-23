package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;

import com.example.demo.shared.events.ResubmissionPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.AbandonPolicy;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * イベント出版を、リスナーの {@code on} に付けた {@link ResubmissionPolicy} の方針に対応づけ、方針の上限を超えて失敗した出版を {@code
 * ABANDONED} にする（ADR-079）。
 *
 * <p>Spring Modulith はリスナーが失敗するたびに {@link #shouldAbandon} を呼び、{@code ABANDON} なら出版を {@code FAILED}
 * でなく {@code ABANDONED} にする。{@code completion-mode: archive} では、{@code ABANDONED} の出版は archive
 * の表へ移る。
 *
 * <p>出版はリスナーの ID（{@code listener_id}、{@link TargetEventPublication#getTargetIdentifier()}）で区別する。
 * Spring Modulith 2.2.0 は、{@code TransactionalApplicationListener.getListenerId()} を ID にし、Spring
 * Framework は、注釈の {@code id} が空ならメソッドの完全修飾名と引数の型（{@code <クラス>.<メソッド>(<型>,...)}）を ID にする。 起動のときに
 * bean のメソッドを走査して同じ ID を作る。
 */
@Slf4j
@Component
class ResubmissionPolicies implements AbandonPolicy {

  /** 方針の設定値。 */
  private final EventPublicationResubmissionConfig.Properties properties;

  /** リスナーの ID から方針の名前。方針を指定しないリスナーは含まない。 */
  private final Map<String, String> policyNames;

  /** Spring Boot が構成した jOOQ。 */
  private final DSLContext dsl;

  /** 方針の上限を超えて ABANDONED にした出版の数。 */
  private final Counter exhausted;

  /* package */ ResubmissionPolicies(
      final ConfigurableListableBeanFactory beanFactory,
      final EventPublicationResubmissionConfig.Properties properties,
      final DSLContext dsl,
      final MeterRegistry meterRegistry) {
    this.properties = properties;
    this.policyNames = Map.copyOf(scan(beanFactory, properties));
    this.dsl = dsl;
    this.exhausted =
        Counter.builder("event.publication.resubmission.exhausted")
            .description("方針の再投入の回数の上限を超えて失敗し、ABANDONED にした出版の数")
            .register(meterRegistry);
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

  /** 出版のリスナーの ID。Spring Modulith 2.2.0 の再投入と諦めの判断は {@link TargetEventPublication} を渡す。 */
  /* package */ static String listenerIdOf(final EventPublication publication) {
    return publication instanceof TargetEventPublication target
        ? target.getTargetIdentifier().getValue()
        : "";
  }

  /**
   * 出版の方針の上限に達しておらず、最後の試行から次の待ち時間を過ぎたか。
   *
   * <p>上限を超えて失敗した出版は {@link #shouldAbandon} が {@code ABANDONED} にするため、ここに来ない。
   * 回数を見るのは、失敗を記録せずに上限を超えて {@code FAILED} に戻った出版（最後の試行の途中で落ちたか、Staleness Monitor
   * が処理中に戻した出版）を、上限を超えて再投入しないためである（ADR-079）。
   */
  /* package */ boolean isDue(final EventPublication publication, final Instant now) {
    final EventPublicationResubmissionConfig.Policy policy = policyOf(listenerIdOf(publication));
    final int attempts = publication.getCompletionAttempts();
    return attempts <= policy.maxResubmissions()
        && lastAttempt(publication)
            .plus(policy.waitBefore(properties.waitAge(), attempts))
            .isBefore(now);
  }

  /**
   * 失敗した試行で {@code completion_attempts} がリスナーの方針の {@code max-resubmissions} を超えたら、ERROR を記録して
   * {@code ABANDON} を返す。超えていなければ {@code RETAIN} を返し、Spring Modulith の既定の判断（{@code
   * abandon-after-attempts}）を使わない。
   *
   * <p>回数は DB の行から読む。再投入した出版が失敗したとき、渡される出版の {@code getCompletionAttempts()} は再投入の前に読んだ値のままで、 再投入で 1
   * 増えた行の値より 1 小さいためである（{@code ResubmissionOptionsLearningTest}）。
   */
  @Override
  public Decision shouldAbandon(final EventPublication publication) {
    final String listenerId = listenerIdOf(publication);
    final @Nullable Integer attempts =
        dsl.select(EVENT_PUBLICATION.COMPLETION_ATTEMPTS)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(publication.getIdentifier()))
            .fetchOne(EVENT_PUBLICATION.COMPLETION_ATTEMPTS);
    if (attempts == null || attempts <= policyOf(listenerId).maxResubmissions()) {
      return Decision.RETAIN;
    }
    exhausted.increment();
    log.atError()
        .addKeyValue("event_publication.id", publication.getIdentifier().toString())
        .addKeyValue("event_publication.event_type", publication.getEvent().getClass().getName())
        .addKeyValue("event_publication.listener_id", listenerId)
        .addKeyValue("event_publication.completion_attempts", attempts)
        .log("Event publication resubmission exhausted");
    return Decision.ABANDON;
  }

  /** 最後の試行の時刻。再投入していなければ最初の試行の時刻。 */
  private static Instant lastAttempt(final EventPublication publication) {
    return Objects.requireNonNullElse(
        publication.getLastResubmissionDate(), publication.getPublicationDate());
  }
}
