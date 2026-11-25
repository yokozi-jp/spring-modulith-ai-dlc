package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.events.ListenerIds;
import com.example.demo.shared.events.ResubmissionPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.modulith.events.AbandonPolicy.Decision;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;

/**
 * {@link ResubmissionPolicies} が bean を走査してリスナーの ID と方針の名前を対応づけることと、再投入してよいかの判断を検証する。
 *
 * <p>DB と Spring の文脈は使わず、{@link DefaultListableBeanFactory} に bean の定義だけを登録して作る。
 */
// テストのリスナーの受信メソッドは on と命名する規約のため。
@SuppressWarnings("PMD.ShortMethodName")
class ResubmissionPoliciesTest {

  /** 型を解決できない bean のクラス名。 */
  private static final String MISSING_CLASS = "com.example.demo.NoSuchClass";

  /** 設定にある方針の名前。 */
  private static final String PAYMENT = "payment";

  /** 最初の配信の失敗で諦める方針の名前（abandon-after-attempts = 1）。 */
  private static final String ONCE = "once";

  /** 3 回で諦める方針の名前（abandon-after-attempts = 3）。 */
  private static final String LIMITED = "limited";

  /** 最後の試行の時刻。マイクロ秒の精度にする。 */
  private static final Instant T0 = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** 再投入の回数の上限。 */
  private static final int MAX_RESUBMISSIONS = 5;

  /** 方針の設定値。{@code default} と、倍率の違う {@code payment} を持つ。 */
  private static final EventPublicationResubmissionConfig.Properties PROPERTIES =
      ResubmissionTestSupport.properties(
          Map.of(
              EventPublicationResubmissionConfig.DEFAULT_POLICY,
              new EventPublicationResubmissionConfig.Policy(2, Duration.ofHours(1), null),
              PAYMENT,
              new EventPublicationResubmissionConfig.Policy(3, Duration.ofHours(1), null),
              ONCE,
              new EventPublicationResubmissionConfig.Policy(2, Duration.ofHours(1), 1),
              LIMITED,
              new EventPublicationResubmissionConfig.Policy(2, Duration.ofHours(1), 3)));

  @Test
  @DisplayName("型を解決できない bean があっても起動を失敗させず、他の bean の方針を対応づける")
  void unresolvableBeanIsSkipped() throws NoSuchMethodException {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("missing", new RootBeanDefinition(MISSING_CLASS));
    beanFactory.registerBeanDefinition("payment", new RootBeanDefinition(PaymentListener.class));
    // 存在しないクラス名の bean は CannotLoadBeanClassException になる。これが読み飛ばしの前提である。
    assertThatThrownBy(() -> AutoProxyUtils.determineTargetClass(beanFactory, "missing"))
        .isInstanceOf(CannotLoadBeanClassException.class);

    final ResubmissionPolicies policies = policiesOf(beanFactory);

    assertThat(
            policies.nameOf(
                ListenerIds.fromMethod(
                    PaymentListener.class.getDeclaredMethod("on", String.class))))
        .isEqualTo(PAYMENT);
  }

  @Test
  @DisplayName("@ApplicationModuleListener のないメソッドに @ResubmissionPolicy を付けると、構築が失敗する")
  void policyOnNonListenerMethodFailsStartup() {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("plain", new RootBeanDefinition(NonListener.class));

    assertThatThrownBy(() -> policiesOf(beanFactory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@ApplicationModuleListener")
        .hasMessageContaining("NonListener.on");
  }

  @Test
  @DisplayName("注釈の id を明示したリスナーは、その id で方針の名前を引ける")
  void explicitListenerIdIsUsed() {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("named", new RootBeanDefinition(NamedListener.class));

    final ResubmissionPolicies policies = policiesOf(beanFactory);

    assertThat(policies.nameOf("custom-id")).isEqualTo(PAYMENT);
    assertThat(policies.nameOf("other-id"))
        .isEqualTo(EventPublicationResubmissionConfig.DEFAULT_POLICY);
  }

  @Test
  @DisplayName("id が空なら、リスナーの ID はメソッドの完全修飾名と引数の型になる")
  void emptyIdFallsBackToQualifiedMethodNameAndParameterTypes() throws NoSuchMethodException {
    final String id =
        ListenerIds.fromMethod(PaymentListener.class.getDeclaredMethod("on", String.class));

    assertThat(id).isEqualTo(PaymentListener.class.getName() + ".on(java.lang.String)");
  }

  @Test
  @DisplayName("リスナーの ID を持たない出版は default の方針の待ち時間で、リスナーの出版はその方針の待ち時間で判断する")
  void waitFollowsPolicyOfListenerAndDefaultsWithoutListener() {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("named", new RootBeanDefinition(NamedListener.class));
    final ResubmissionPolicies policies = policiesOf(beanFactory);
    // 2 回目の再投入の前の待ち時間は、default が 5 分 × 2 = 10 分、payment が 5 分 × 3 = 15 分。
    final Instant defaultWait = T0.plus(Duration.ofMinutes(10));

    assertThat(policies.isDue(failed(2), defaultWait)).as("default のちょうど待ち時間").isFalse();
    assertThat(policies.isDue(failed(2), defaultWait.plus(1, ChronoUnit.MICROS)))
        .as("default の待ち時間の 1 マイクロ秒後")
        .isTrue();
    assertThat(policies.isDue(failed("custom-id", 2), defaultWait.plus(1, ChronoUnit.MICROS)))
        .as("payment の待ち時間の前")
        .isFalse();
  }

  @ParameterizedTest(name = "listener={0}, attempts={1}")
  @CsvSource({
    // abandon-after-attempts = 1 は最初の配信の失敗で諦め、途中で落ちて FAILED に戻った出版も再投入しない。
    "once-id, 1, ABANDON, false",
    // abandon-after-attempts = 3 は 3 回目の再投入まで再投入し、その失敗で諦める。
    "limited-id, 2, DEFAULT, true",
    "limited-id, 3, ABANDON, true",
    "limited-id, 4, ABANDON, false",
    // 方針に指定がなければ全体の値（5）に従う。
    "unknown, 4, DEFAULT, true",
    "unknown, 5, ABANDON, true",
    "unknown, 6, ABANDON, false"
  })
  @DisplayName("諦めるかと再投入するかは、リスナーの方針の abandon-after-attempts で決まる")
  void abandonAndDueFollowListenerLimit(
      final String listenerId, final int attempts, final Decision abandon, final boolean due) {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("once", new RootBeanDefinition(OnceListener.class));
    beanFactory.registerBeanDefinition("limited", new RootBeanDefinition(LimitedListener.class));
    final ResubmissionPolicies policies = policiesOf(beanFactory);

    assertThat(policies.shouldAbandon(failed(listenerId, attempts))).as("諦めるか").isEqualTo(abandon);
    assertThat(policies.isDue(failed(listenerId, attempts), T0.plus(Duration.ofDays(1))))
        .as("待ち時間を過ぎた後に再投入するか")
        .isEqualTo(due);
  }

  @Test
  @DisplayName("Gauge に渡す上限は、既定と違うリスナーだけを持ち、abandon-after-attempts = 1 のリスナーは 0 である")
  void maxResubmissionsByListenerListsOnlyListenersWithOwnLimit() {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("once", new RootBeanDefinition(OnceListener.class));
    beanFactory.registerBeanDefinition("limited", new RootBeanDefinition(LimitedListener.class));
    beanFactory.registerBeanDefinition(PAYMENT, new RootBeanDefinition(PaymentListener.class));

    final ResubmissionPolicies policies = policiesOf(beanFactory);

    assertThat(policies.maxResubmissionsByListener())
        .isEqualTo(
            new ResubmissionPolicies.MaxResubmissions(
                Map.of("once-id", 0, "limited-id", 3), MAX_RESUBMISSIONS));
  }

  /** DB を使わない構築。 */
  private static ResubmissionPolicies policiesOf(
      final ConfigurableListableBeanFactory beanFactory) {
    return new ResubmissionPolicies(
        beanFactory,
        PROPERTIES,
        new EventPublicationResubmissionConfig.AbandonProperties(MAX_RESUBMISSIONS));
  }

  /** 最後の試行が {@link #T0} で、{@link TargetEventPublication} でない FAILED の出版。 */
  private static EventPublication failed(final int attempts) {
    return ResubmissionTestSupport.stubPublication(
        Mockito.mock(EventPublication.class, Mockito.CALLS_REAL_METHODS),
        UUID.randomUUID(),
        attempts,
        T0.minus(Duration.ofDays(1)),
        T0);
  }

  /** 最後の試行が {@link #T0} で、リスナーの ID を持つ FAILED の出版。 */
  private static EventPublication failed(final String listenerId, final int attempts) {
    final TargetEventPublication publication =
        Mockito.mock(TargetEventPublication.class, Mockito.CALLS_REAL_METHODS);
    Mockito.doReturn(PublicationTargetIdentifier.of(listenerId))
        .when(publication)
        .getTargetIdentifier();
    return ResubmissionTestSupport.stubPublication(
        publication, UUID.randomUUID(), attempts, T0.minus(Duration.ofDays(1)), T0);
  }

  /** 方針を指定し、id を明示しないリスナー。 */
  /* package */ static class PaymentListener {

    @ApplicationModuleListener
    @ResubmissionPolicy(PAYMENT)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }

  /** 最初の配信の失敗で諦める方針のリスナー。 */
  /* package */ static class OnceListener {

    @ApplicationModuleListener(id = "once-id")
    @ResubmissionPolicy(ONCE)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }

  /** 3 回で諦める方針のリスナー。 */
  /* package */ static class LimitedListener {

    @ApplicationModuleListener(id = "limited-id")
    @ResubmissionPolicy(LIMITED)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }

  /** 方針を指定し、id を明示したリスナー。 */
  /* package */ static class NamedListener {

    @ApplicationModuleListener(id = "custom-id")
    @ResubmissionPolicy(PAYMENT)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }

  /** {@code @ApplicationModuleListener} を持たないのに方針を付けたクラス。 */
  /* package */ static class NonListener {

    @ResubmissionPolicy(PAYMENT)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }
}
