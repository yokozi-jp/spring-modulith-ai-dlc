package com.example.demo.testkit;

import com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager;
import org.jooq.DSLContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link DatabaseTest} のロールバック用のトランザクションに、{@link AsTenant#TENANT} のテナントを設定する。
 *
 * <p>本番はトランザクションマネージャーが、トランザクションを始める時点で束縛したテナントを設定する。テストのトランザクションはテストのメソッドより前に始まり、{@code
 * ScopedValue} の束縛の中に入れられないため、始まった直後に同じ {@link ModuleRoleTransactionManager#setLocal} で設定する。
 * テストのトランザクションがないメソッド（{@code Propagation.NOT_SUPPORTED}）には何もしない。
 */
public final class DatabaseTestTenantListener implements TestExecutionListener, Ordered {

  @Override
  public int getOrder() {
    // TransactionalTestExecutionListener（4000）がトランザクションを始めた後に動かす。
    return LOWEST_PRECEDENCE;
  }

  @Override
  public void beforeTestMethod(final TestContext testContext) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    testContext
        .getApplicationContext()
        .getBean(DSLContext.class)
        .connection(connection ->
            ModuleRoleTransactionManager.setLocal(connection, null, AsTenant.TENANT.toString()));
  }
}
