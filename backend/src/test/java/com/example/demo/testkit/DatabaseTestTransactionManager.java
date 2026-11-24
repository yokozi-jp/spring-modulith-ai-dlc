package com.example.demo.testkit;

import com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager;
import com.example.demo.shared.tenancy.TenantContext;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * {@link DatabaseTest} のロールバック用のトランザクションを、{@link AsTenant#TENANT} のテナントを束縛して始めるトランザクションマネージャー。
 *
 * <p>本番のトランザクションマネージャーは、業務のモジュールのトランザクションをテナントの束縛の中でだけ始める（ADR-107）。テストのトランザクションはテストのメソッドより前に {@code
 * TransactionalTestExecutionListener} が始め、{@code ScopedValue} の束縛の中に入れられない。そのため、{@link
 * DatabaseTest} が付ける {@link #LABEL} のトランザクションだけ、始める間だけテナントを束縛して本番の処理に渡す。
 */
public final class DatabaseTestTransactionManager extends ModuleRoleTransactionManager {

  /** テストのトランザクションの目印。 */
  public static final String LABEL = "test-tenant";

  /** トランザクションマネージャーは {@code Serializable} だが、直列化しない。 */
  private static final long serialVersionUID = 1L;

  /** 接続先のデータソースで作る。 */
  public DatabaseTestTransactionManager(final DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected void prepareTransactionalConnection(
      final Connection connection, final TransactionDefinition definition) throws SQLException {
    if (definition instanceof TransactionAttribute attribute
        && attribute.getLabels().contains(LABEL)) {
      TenantContext.<Void, SQLException>call(
          AsTenant.TENANT,
          () -> {
            super.prepareTransactionalConnection(connection, definition);
            return null;
          });
    } else {
      super.prepareTransactionalConnection(connection, definition);
    }
  }

  /** 本番の {@link ModuleRoleTransactionManager.Config} の代わりに、この子クラスをトランザクションマネージャーにする。 */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Config {

    /** 本番と同じく、{@code TransactionManagerCustomizers} を適用し、インフラのロールにする。 */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ModuleRoleTransactionManager transactionManager(
        final DataSource dataSource,
        final ObjectProvider<TransactionManagerCustomizers> customizers) {
      final ModuleRoleTransactionManager manager = new DatabaseTestTransactionManager(dataSource);
      customizers.ifAvailable(applicable -> applicable.customize(manager));
      return manager;
    }
  }
}
