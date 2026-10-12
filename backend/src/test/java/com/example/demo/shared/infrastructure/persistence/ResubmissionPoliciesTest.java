package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.events.ResubmissionPolicy;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * {@link ResubmissionPolicies} が bean を走査して、リスナーの ID と方針の名前を対応づけることを検証する。
 *
 * <p>DB と Spring の文脈は使わず、{@link DefaultListableBeanFactory} に bean の定義だけを登録して作る。
 */
class ResubmissionPoliciesTest {

  /** 型を解決できない bean のクラス名。 */
  private static final String MISSING_CLASS = "com.example.demo.NoSuchClass";

  /** 設定にある方針の名前。 */
  private static final String PAYMENT = "payment";

  /** 方針の設定値。{@code default} と {@code payment} を持つ。 */
  private static final EventPublicationResubmissionConfig.Properties PROPERTIES =
      new EventPublicationResubmissionConfig.Properties(
          Duration.ofMinutes(5),
          Duration.ofHours(4),
          Duration.ofMinutes(1),
          100,
          Duration.ofMinutes(10),
          Map.of(
              EventPublicationResubmissionConfig.DEFAULT_POLICY,
              new EventPublicationResubmissionConfig.Policy(5, 2, Duration.ofHours(1)),
              PAYMENT,
              new EventPublicationResubmissionConfig.Policy(3, 2, Duration.ofHours(1))));

  @Test
  @DisplayName("型を解決できない bean があっても起動を失敗させず、他の bean の方針を対応づける")
  void unresolvableBeanIsSkipped() throws NoSuchMethodException {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("missing", new RootBeanDefinition(MISSING_CLASS));
    beanFactory.registerBeanDefinition("payment", new RootBeanDefinition(PaymentListener.class));
    // 存在しないクラス名の bean は CannotLoadBeanClassException になる。これが読み飛ばしの前提である。
    assertThatThrownBy(() -> AutoProxyUtils.determineTargetClass(beanFactory, "missing"))
        .isInstanceOf(CannotLoadBeanClassException.class);

    final ResubmissionPolicies policies = new ResubmissionPolicies(beanFactory, PROPERTIES);

    assertThat(
            policies.nameOf(
                ResubmissionPolicies.listenerId(
                    PaymentListener.class.getDeclaredMethod("on", String.class))))
        .isEqualTo(PAYMENT);
  }

  @Test
  @DisplayName("@ApplicationModuleListener のないメソッドに @ResubmissionPolicy を付けると、構築が失敗する")
  void policyOnNonListenerMethodFailsStartup() {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("plain", new RootBeanDefinition(NonListener.class));

    assertThatThrownBy(() -> new ResubmissionPolicies(beanFactory, PROPERTIES))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("@ApplicationModuleListener")
        .hasMessageContaining("NonListener.on");
  }

  @Test
  @DisplayName("注釈の id を明示したリスナーは、その id で方針の名前を引ける")
  void explicitListenerIdIsUsed() {
    final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    beanFactory.registerBeanDefinition("named", new RootBeanDefinition(NamedListener.class));

    final ResubmissionPolicies policies = new ResubmissionPolicies(beanFactory, PROPERTIES);

    assertThat(policies.nameOf("custom-id")).isEqualTo(PAYMENT);
    assertThat(policies.nameOf("other-id"))
        .isEqualTo(EventPublicationResubmissionConfig.DEFAULT_POLICY);
  }

  @Test
  @DisplayName("id が空なら、リスナーの ID はメソッドの完全修飾名と引数の型になる")
  void emptyIdFallsBackToQualifiedMethodNameAndParameterTypes() throws NoSuchMethodException {
    final String id =
        ResubmissionPolicies.listenerId(
            PaymentListener.class.getDeclaredMethod("on", String.class));

    assertThat(id).isEqualTo(PaymentListener.class.getName() + ".on(java.lang.String)");
  }

  /** 方針を指定し、id を明示しないリスナー。 */
  // 受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class PaymentListener {

    @ApplicationModuleListener
    @ResubmissionPolicy(PAYMENT)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }

  /** 方針を指定し、id を明示したリスナー。 */
  // 受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class NamedListener {

    @ApplicationModuleListener(id = "custom-id")
    @ResubmissionPolicy(PAYMENT)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }

  /** {@code @ApplicationModuleListener} を持たないのに方針を付けたクラス。 */
  // 受信メソッドは on と命名する規約のため。
  @SuppressWarnings("PMD.ShortMethodName")
  /* package */ static class NonListener {

    @ResubmissionPolicy(PAYMENT)
    /* package */ void on(final String event) {
      // 呼ばれない。
    }
  }
}
