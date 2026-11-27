package com.example.demo.shared.locking;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jooq.JooqLockProvider;
import org.jooq.DSLContext;
import org.jooq.conf.MappedSchema;
import org.jooq.conf.RenderMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ShedLock の jOOQ の provider と、ロックの実行を構成する（ADR-079）。
 *
 * <p>provider はロックの期限を DB の時刻で書き込んで比べるため、インスタンスの時計がずれても排他が崩れない。 ロックの操作は 1
 * 文ずつのトランザクションで終わり、接続を固定しないため、接続を多重化するプロキシ（RDS Proxy、PgBouncer のトランザクションモード）の後ろでも動く。
 */
@Configuration(proxyBeanMethods = false)
class LockConfig {

  /** ロックの表があるスキーマ。ShedLock の表の名前はスキーマを持たないため、SQL を組み立てるときに付ける。 */
  private static final String LOCK_SCHEMA = "modulith";

  /**
   * ロックの provider。ロックの表は {@code modulith.shedlock} にする。
   *
   * <p>ロックの操作は、呼び出し元のトランザクションに入らないよう、新しいトランザクション（{@code REQUIRES_NEW}）で実行する。
   * 呼び出し元のトランザクションに入ると、ロックの行がそのトランザクションの終わりまでコミットされず、他のインスタンスに見えないためである（ShedLock 7.9 の変更）。
   */
  @Bean
  /* package */ LockProvider lockProvider(
      final DSLContext dsl, final PlatformTransactionManager transactionManager) {
    final TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
    requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new JooqLockProvider(
        lockDsl(dsl),
        (lockDsl, callable) -> requiresNew.execute(status -> lockDsl.transactionResult(callable)));
  }

  /** ロックを取って実行する。定期の再投入、手での 1 回の再投入、冪等性キーの掃除が共有する。 */
  @Bean
  /* package */ LockingTaskExecutor lockingTaskExecutor(final LockProvider lockProvider) {
    return new DefaultLockingTaskExecutor(lockProvider);
  }

  /**
   * Spring Boot の jOOQ の構成（ADR-070 の ExecuteListener、例外の変換）を保ち、スキーマのない ShedLock の表を {@code
   * modulith} に描画する。
   *
   * <p>search_path（HikariCP の {@code schema}）はセッションの設定で、接続を多重化するプロキシの後ろでは保たれないため、頼らない。
   */
  /* package */ static DSLContext lockDsl(final DSLContext dsl) {
    return dsl.configuration()
        .deriveSettings(settings ->
            settings.withRenderMapping(
                new RenderMapping()
                    .withSchemata(new MappedSchema().withInput("").withOutput(LOCK_SCHEMA))))
        .dsl();
  }
}
