package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.demo.shared.tenant.TenantContext;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.testkit.CommittedDatabaseTest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * トランザクションマネージャーが、トランザクションの名前からモジュールのロールへ、束縛したテナントへ切り替え、commit と rollback
 * の後にプールの接続へロールとテナントを残さないことを確かめる（ADR-094、ADR-107）。
 *
 * <p>プールの接続は少数を使い回すため、モジュールのロールのトランザクションの後に、名前のないトランザクションを繰り返し、すべてアプリロールで動くことを見る。
 */
@CommittedDatabaseTest
class ModuleRoleTransactionTest {

  /** 試す回数。プールの上限（テストは 4）より多くして、すべての接続を通す。 */
  private static final int ROUNDS = 12;

  /** ordering のトランザクションの名前。 */
  private static final String ORDERING = "com.example.demo.ordering.application.Probe.handle";

  /** 束縛するテナント。 */
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("7a1c3e5f-2b4d-4f6a-8c0e-1d3f5a7b9c2e"));

  /** 空文字列のテナントを uuid に変換できないときの SQLSTATE（invalid_text_representation）。 */
  private static final String INVALID_UUID = "22P02";

  /** テナントを一度も設定していない接続の SQLSTATE（undefined_object）。 */
  private static final String UNDEFINED_SETTING = "42704";

  /** トランザクションのテナントの設定。一度も設定していない接続では {@code null}。 */
  private static final Field<String> CURRENT_TENANT =
      DSL.field(
          "current_setting({0}, true)",
          String.class, DSL.val(ModuleRoleTransactionManager.TENANT_SETTING));

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

  @Test
  @DisplayName("テナントを束縛したトランザクションはそのテナントを設定し、commit と rollback の後にプールの接続へ残さない")
  void tenantIsSetOnlyForBoundTransactions() {
    for (int round = 0; round < ROUNDS; round++) {
      assertThat(TenantContext.call(TENANT, () -> currentTenantIn(ORDERING)))
          .as("束縛したトランザクションのテナント")
          .isEqualTo(TENANT.toString());
      assertThat(currentTenantIn(ORDERING)).as("commit の後のテナント").isIn("", null);
      assertThatThrownBy(() ->
              TenantContext.call(
                  TENANT,
                  () ->
                      template(ORDERING)
                          .execute(status -> {
                            throw new IllegalStateException("rollback");
                          })))
          .isInstanceOf(IllegalStateException.class);
      assertThat(currentTenantIn(null)).as("rollback の後のテナント").isIn("", null);
    }
  }

  @Test
  @DisplayName("テナントの設定が空文字列で残った接続でも、テナントのないトランザクションの業務の表の読み取りはエラーになる")
  void emptyTenantLeftOnPooledConnectionFailsClosed() {
    final List<String> leftovers = new ArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      TenantContext.call(TENANT, () -> currentTenantIn(ORDERING));
      final Throwable thrown =
          catchThrowable(() ->
              template(ORDERING)
                  .executeWithoutResult(status -> {
                    leftovers.add(dsl.fetchValue(CURRENT_TENANT));
                    dsl.fetchCount(T_ORDER);
                  }));

      assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
          .as("テナントのない読み取りの例外: %s", thrown)
          .isInstanceOfSatisfying(
              SQLException.class,
              cause -> assertThat(cause.getSQLState()).isIn(INVALID_UUID, UNDEFINED_SETTING));
    }
    assertThat(leftovers)
        .as("テナントのないトランザクションに残っていた設定")
        .allSatisfy(leftover -> assertThat(leftover).isIn("", null))
        .contains("");
  }

  private String currentTenantIn(final String transactionName) {
    return template(transactionName).execute(status -> dsl.fetchValue(CURRENT_TENANT));
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
