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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * トランザクションマネージャーが、トランザクションの名前からモジュールのロールへ、束縛したテナントへ切り替え、commit と rollback
 * の後にプールの接続へロールとテナントを残さないことと、業務のモジュールのトランザクションをテナントなしでは始めないことを確かめる（ADR-094、ADR-107）。
 *
 * <p>プールの接続は少数を使い回すため、モジュールのロールのトランザクションの後に、名前のないトランザクションを繰り返し、すべてアプリロールで動くことを見る。
 */
@CommittedDatabaseTest
@SuppressWarnings("PMD.TooManyMethods")
class ModuleRoleTransactionTest {

  /** 試す回数。プールの上限（テストは 4）より多くして、すべての接続を通す。 */
  private static final int ROUNDS = 12;

  /** ordering のモジュールのロール。 */
  private static final String ORDERING_ROLE = "ordering_module";

  /** ordering のトランザクションの名前。 */
  private static final String ORDERING = "com.example.demo.ordering.application.Probe.handle";

  /** 束縛するテナント。 */
  private static final TenantId TENANT =
      new TenantId(UUID.fromString("7a1c3e5f-2b4d-4f6a-8c0e-1d3f5a7b9c2e"));

  /** ordering のトランザクションの中のロールとテナント。 */
  private static final Session IN_ORDERING = new Session(ORDERING_ROLE, TENANT.toString());

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
  @DisplayName("モジュールの名前のトランザクションは、モジュールのロールと束縛したテナントで動き、commit の後にプールの接続へ残さない")
  void commitRestoresAppRoleWithoutTenant() {
    for (int round = 0; round < ROUNDS; round++) {
      assertThat(TenantContext.call(TENANT, () -> sessionIn(ORDERING))).isEqualTo(IN_ORDERING);
      assertAppRoleWithoutTenant(sessionIn(null));
    }
  }

  @Test
  @DisplayName("モジュールの名前のトランザクションが失敗して rollback しても、ロールとテナントをプールの接続へ残さない")
  void rollbackRestoresAppRoleWithoutTenant() {
    final List<Session> inside = new ArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      assertThatThrownBy(() ->
              TenantContext.call(
                  TENANT,
                  () ->
                      template(ORDERING)
                          .execute(status -> {
                            inside.add(session());
                            throw new IllegalStateException("rollback");
                          })))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("rollback");
      assertAppRoleWithoutTenant(sessionIn(null));
    }
    assertThat(inside).as("rollback したトランザクションの中").hasSize(ROUNDS).containsOnly(IN_ORDERING);
  }

  @Test
  @DisplayName("名前のないトランザクションと、モジュールでない名前のトランザクションは、アプリロールで動く")
  void unnamedAndNonModuleTransactionsRunAsAppRole() {
    assertThat(roleIn(null)).isEqualTo(appRole);
    assertThat(roleIn("com.example.demo.shared.infrastructure.persistence.Probe.run"))
        .isEqualTo(appRole);
    assertThat(dsl.fetchValue("select current_user")).isEqualTo(appRole);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"product", "ordering", "payment"})
  @DisplayName("業務の表を持つモジュールのトランザクションは、テナントを束縛していなければ、SQL を流す前に失敗する")
  void businessModuleTransactionWithoutTenantIsRejected(final String module) {
    final String name = "com.example.demo." + module + ".application.Probe.handle";
    final List<Object> users = new ArrayList<>();

    final Throwable thrown = catchThrowable(() -> users.add(roleIn(name)));

    assertThat(users).as("始まったトランザクションの利用者").isEmpty();
    assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
        .as("例外: %s", thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(name);
  }

  @Test
  @DisplayName("tenancy のトランザクションは、テナントを束縛していなくても tenancy のロールで動く")
  void tenancyTransactionRunsWithoutTenant() {
    assertThat(roleIn("com.example.demo.tenancy.application.Probe.handle"))
        .isEqualTo("tenancy_module");
  }

  @Test
  @DisplayName("jOOQ の transaction は、呼び出し元のトランザクションの名前のロールで動く")
  void jooqTransactionJoinsNamedTransaction() {
    final Object user =
        TenantContext.call(
            TENANT,
            () ->
                template(ORDERING)
                    .execute(status ->
                        dsl.transactionResult(cfg -> cfg.dsl().fetchValue("select current_user"))));

    assertThat(user).isEqualTo(ORDERING_ROLE);
    assertThat(IntStream.range(0, ROUNDS).mapToObj(round -> roleIn(null)))
        .allSatisfy(role -> assertThat(role).isEqualTo(appRole));
  }

  @Test
  @DisplayName("テナントの設定が空文字列で残った接続でも、テナントを設定しないモジュールのロールの業務の表の読み取りはエラーになる")
  void emptyTenantLeftOnPooledConnectionFailsClosed() {
    final List<String> leftovers = new ArrayList<>();
    for (int round = 0; round < ROUNDS; round++) {
      TenantContext.call(TENANT, () -> sessionIn(ORDERING));
      final Throwable thrown =
          catchThrowable(() ->
              template(null)
                  .executeWithoutResult(status -> {
                    leftovers.add(dsl.fetchValue(CURRENT_TENANT));
                    // マネージャーはテナントのない ordering のトランザクションを始めないため、DB の 2 段目の防御をロールだけで確かめる。
                    dsl.connection(connection ->
                        ModuleRoleTransactionManager.setLocal(
                            connection, ModuleRoles.roleOf("ordering"), null));
                    dsl.fetchCount(T_ORDER);
                  }));

      final String expected = leftovers.getLast() == null ? UNDEFINED_SETTING : INVALID_UUID;
      assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
          .as("残っていた設定 %s のテナントのない読み取りの例外: %s", leftovers.getLast(), thrown)
          .isInstanceOfSatisfying(
              SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo(expected));
    }
    assertThat(leftovers)
        .as("テナントのないトランザクションに残っていた設定")
        .allSatisfy(leftover -> assertThat(leftover).isIn("", null))
        .contains("");
  }

  private void assertAppRoleWithoutTenant(final Session session) {
    assertThat(session.role()).as("トランザクションの後のロール").isEqualTo(appRole);
    assertThat(session.tenant()).as("トランザクションの後のテナント").isIn("", null);
  }

  private String roleIn(final String transactionName) {
    return sessionIn(transactionName).role();
  }

  private Session sessionIn(final String transactionName) {
    return template(transactionName).execute(status -> session());
  }

  private Session session() {
    return dsl.select(DSL.field("current_user", String.class), CURRENT_TENANT)
        .fetchSingle(row -> new Session(row.value1(), row.value2()));
  }

  private TransactionTemplate template(final String transactionName) {
    final TransactionTemplate template = new TransactionTemplate(transactionManager);
    if (transactionName != null) {
      template.setName(transactionName);
    }
    return template;
  }

  /**
   * トランザクションの中のロールとテナントの設定。
   *
   * @param role {@code current_user}
   * @param tenant テナントの設定。一度も設定していない接続では {@code null}
   */
  private record Session(String role, @Nullable String tenant) {}
}
