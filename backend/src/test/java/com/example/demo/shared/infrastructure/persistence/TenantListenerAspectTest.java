package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import com.example.demo.testkit.AsTenant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** {@link TenantListenerAspect} の束縛が、リスナーのトランザクションより外側で効き、呼び出しの後に残らないことを検証する（ADR-105）。 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
// fixture の Listener は、pointcut の対象にするためメソッドを public にする。
@SuppressWarnings({"PMD.UnitTestShouldIncludeAssert", "PMD.PublicMemberInNonPublicType"})
class TenantListenerAspectTest {

  /** イベントのテナント。 */
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("5d2e8f4a-1b3c-4d6e-9f70-8a1b2c3d4e5f"));

  @Test
  @DisplayName("リスナーのトランザクションが始まる時点と確定する時点で、イベントのテナントが束縛されている")
  void tenantIsBoundWhileListenerTransactionBeginsAndCommits() {
    contextRunner()
        .run(context -> {
          context.getBean(OrderShippedListener.class).on(new OrderShipped(TENANT));

          final RecordingTransactionManager transactionManager =
              context.getBean(RecordingTransactionManager.class);
          assertThat(transactionManager.tenantsAtBegin)
              .as("getTransaction の時点のテナント")
              .containsExactly(TENANT.toString());
          assertThat(transactionManager.tenantsAtCommit)
              .as("commit の時点のテナント")
              .containsExactly(TENANT.toString());
        });
  }

  @Test
  @DisplayName("リスナーの中ではイベントのテナントを読め、リスナーを出ると束縛が外れる")
  void tenantIsBoundOnlyInsideListener() {
    contextRunner()
        .run(context -> {
          final OrderShippedListener listener = context.getBean(OrderShippedListener.class);

          assertThat(listener.on(new OrderShipped(TENANT))).isEqualTo(TENANT);

          assertThatThrownBy(TenantContext::current)
              .as("リスナーを出た後")
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("tenant is not bound");
        });
  }

  @Test
  @DisplayName("イベントが TenantScoped でないリスナーは、IllegalStateException で拒否する")
  void eventWithoutTenantIsRejected() {
    contextRunner()
        .run(context ->
            assertThatThrownBy(() -> context.getBean(UntenantedListener.class).on("shipped"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must implement TenantScoped"));
  }

  @Test
  // 型の上は null でない値を、JSON から読んだ値を模して null にする。
  @SuppressWarnings("NullAway")
  @DisplayName("イベントのテナントが null（項目のない古い出版を読んだ場合）なら、リスナーを呼ばず、イベントの型を示す IllegalStateException で拒否する")
  void eventWithNullTenantIsRejected() {
    contextRunner()
        .run(context -> {
          assertThatThrownBy(
                  () -> context.getBean(OrderShippedListener.class).on(new OrderShipped(null)))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining(OrderShipped.class.getName());

          assertThat(context.getBean(RecordingTransactionManager.class).tenantsAtBegin)
              .as("リスナーのトランザクションの開始")
              .isEmpty();
        });
  }

  private static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AopAutoConfiguration.class, TransactionAutoConfiguration.class))
        .withUserConfiguration(TenantListenerAspect.class)
        .withBean(RecordingTransactionManager.class)
        .withBean(OrderShippedListener.class)
        .withBean(UntenantedListener.class);
  }

  /**
   * テナントを運ぶイベント。
   *
   * @param tenantId イベントのテナント
   */
  /* package */ record OrderShipped(TenantId tenantId) implements TenantScoped {}

  /** トランザクションを始めた時点と確定した時点のテナントを記録するトランザクションマネージャー。 */
  /* package */ static final class RecordingTransactionManager
      implements PlatformTransactionManager {

    /** getTransaction のたびに記録したテナント。束縛されていなければ {@code unbound}。 */
    private final List<String> tenantsAtBegin = new ArrayList<>();

    /** commit のたびに記録したテナント。束縛されていなければ {@code unbound}。 */
    private final List<String> tenantsAtCommit = new ArrayList<>();

    @Override
    public TransactionStatus getTransaction(final @Nullable TransactionDefinition definition) {
      tenantsAtBegin.add(AsTenant.currentOrUnbound());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(final TransactionStatus status) {
      tenantsAtCommit.add(AsTenant.currentOrUnbound());
    }

    @Override
    public void rollback(final TransactionStatus status) {
      // 記録だけが目的で、戻すものはない。
    }
  }

  /** pointcut とトランザクションの両方の対象になる Listener。 */
  // CGLIB のプロキシを作れるよう、final にしない。
  /* package */ static class OrderShippedListener {

    /** 束縛されたテナントを返す。 */
    @ApplicationModuleListener
    @SuppressWarnings("PMD.ShortMethodName") // ADR-050 が Listener のメソッド名を on に決めている。
    public TenantId on(final OrderShipped event) {
      return TenantContext.current();
    }
  }

  /** イベントが TenantScoped でない Listener。 */
  // CGLIB のプロキシを作れるよう、final にしない。
  /* package */ static class UntenantedListener {

    /** 何もしない。 */
    @ApplicationModuleListener
    @SuppressWarnings("PMD.ShortMethodName") // ADR-050 が Listener のメソッド名を on に決めている。
    public void on(final String event) {
      // Aspect が引数を拒否するため、ここへは届かない。
    }
  }
}
