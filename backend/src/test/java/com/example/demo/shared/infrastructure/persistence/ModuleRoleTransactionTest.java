package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.testkit.CommittedDatabaseTest;
import java.util.stream.IntStream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * トランザクションマネージャーが、トランザクションの名前からモジュールのロールへ切り替え、commit と rollback の後にプールの接続へロールを残さないことを確かめる（ADR-090）。
 *
 * <p>プールの接続は少数を使い回すため、モジュールのロールのトランザクションの後に、名前のないトランザクションを繰り返し、すべてアプリロールで動くことを見る。
 */
@CommittedDatabaseTest
class ModuleRoleTransactionTest {

  /** 試す回数。プールの上限（テストは 4）より多くして、すべての接続を通す。 */
  private static final int ROUNDS = 12;

  /** ordering のトランザクションの名前。 */
  private static final String ORDERING = "com.example.demo.ordering.application.Probe.handle";

  /** アプリロール。 */
  @Value("${spring.datasource.username}")
  private String appRole;

  /** トランザクションマネージャー。 */
  @Autowired private PlatformTransactionManager transactionManager;

  /** jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("モジュールの名前のトランザクションは、モジュールのロールで動き、commit の後にアプリロールへ戻る")
  void commitRestoresAppRole() {
    for (int round = 0; round < ROUNDS; round++) {
      assertThat(currentUserIn(ORDERING)).isEqualTo("ordering_module");
      assertThat(currentUserIn(null)).isEqualTo(appRole);
    }
  }

  @Test
  @DisplayName("モジュールの名前のトランザクションが失敗して rollback しても、アプリロールへ戻る")
  void rollbackRestoresAppRole() {
    for (int round = 0; round < ROUNDS; round++) {
      assertThatThrownBy(() ->
              template(ORDERING)
                  .executeWithoutResult(status -> {
                    throw new IllegalStateException("rollback");
                  }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(currentUserIn(null)).isEqualTo(appRole);
    }
  }

  @Test
  @DisplayName("名前のないトランザクションと、モジュールでない名前のトランザクションは、アプリロールで動く")
  void unnamedAndNonModuleTransactionsRunAsAppRole() {
    assertThat(currentUserIn(null)).isEqualTo(appRole);
    assertThat(currentUserIn("com.example.demo.shared.infrastructure.persistence.Probe.run"))
        .isEqualTo(appRole);
    assertThat(dsl.fetchValue("select current_user")).isEqualTo(appRole);
  }

  @Test
  @DisplayName("jOOQ の transaction は、呼び出し元のトランザクションの名前のロールで動く")
  void jooqTransactionJoinsNamedTransaction() {
    final Object user =
        template(ORDERING)
            .execute(status ->
                dsl.transactionResult(cfg -> cfg.dsl().fetchValue("select current_user")));

    assertThat(user).isEqualTo("ordering_module");
    assertThat(IntStream.range(0, ROUNDS).mapToObj(round -> currentUserIn(null)))
        .allSatisfy(role -> assertThat(role).isEqualTo(appRole));
  }

  private Object currentUserIn(final String transactionName) {
    final TransactionTemplate template = template(transactionName);
    return template.execute(status -> dsl.fetchValue("select current_user"));
  }

  private TransactionTemplate template(final String transactionName) {
    final TransactionTemplate template = new TransactionTemplate(transactionManager);
    if (transactionName != null) {
      template.setName(transactionName);
    }
    return template;
  }
}
