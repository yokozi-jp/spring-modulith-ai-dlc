package com.example.demo.persistence;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static com.example.demo.jooq.product.Tables.M_PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.DatabaseTest;
import java.sql.SQLException;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;

/**
 * モジュールのロールが、別のモジュールの表への書き込みと、依存の方向に逆らう読み取りを DB の権限エラーで止めることを確かめる（ADR-090）。
 *
 * <p>このクラスは機能モジュールの外にあるため、トランザクションの名前からはロールが決まらない。{@link AsModule} で本番と同じ {@code set_config('role',
 * ?, true)} を使って切り替える。
 */
// 生成したテーブルと AssertJ を static import で読みやすくする。
@SuppressWarnings("PMD.TooManyStaticImports")
@DatabaseTest
class ModuleRoleIsolationTest {

  /** 商品のモジュール。 */
  private static final String PRODUCT = "product";

  /** 注文のモジュール。 */
  private static final String ORDERING = "ordering";

  /** 決済のモジュール。 */
  private static final String PAYMENT = "payment";

  /** 権限エラー（insufficient_privilege）の SQLSTATE。 */
  private static final String INSUFFICIENT_PRIVILEGE = "42501";

  /** アプリロール。 */
  @Value("${spring.datasource.username}")
  private String appRole;

  /** テスト用の DB の jOOQ のコンテキスト。テストごとにロールバックする。 */
  @Autowired private DSLContext dsl;

  /** 依存の方向に沿った読み取り（ordering から product、payment から ordering）と、自分のスキーマの読み書きだけが通る。 */
  private static Arguments[] reads() {
    return new Arguments[] {
      Arguments.of(PRODUCT, M_PRODUCT, true),
      Arguments.of(PRODUCT, T_ORDER, false),
      Arguments.of(PRODUCT, T_PAYMENT, false),
      Arguments.of(ORDERING, T_ORDER, true),
      Arguments.of(ORDERING, M_PRODUCT, true),
      Arguments.of(ORDERING, T_PAYMENT, false),
      Arguments.of(PAYMENT, T_PAYMENT, true),
      Arguments.of(PAYMENT, T_ORDER, true),
      Arguments.of(PAYMENT, M_PRODUCT, false),
    };
  }

  @ParameterizedTest(name = "{0} が {1} を読むと、通る: {2}")
  @MethodSource("reads")
  @DisplayName("モジュールは、自分のスキーマと依存先のスキーマだけを読める")
  void readsFollowDependencyDirection(
      final String module, final Table<?> table, final boolean allowed) {
    final Consumer<DSLContext> read = tx -> tx.fetchCount(table);

    if (allowed) {
      assertThatCode(() -> AsModule.run(dsl, module, read)).doesNotThrowAnyException();
    } else {
      assertPermissionDenied(() -> AsModule.run(dsl, module, read));
    }
  }

  /** 自分のスキーマだけに書ける。依存先のスキーマへの書き込みは、読み取りが通る場合も通らない。 */
  private static Arguments[] writes() {
    return new Arguments[] {
      Arguments.of(PRODUCT, M_PRODUCT, true),
      Arguments.of(ORDERING, T_ORDER, true),
      Arguments.of(ORDERING, M_PRODUCT, false),
      Arguments.of(PAYMENT, T_PAYMENT, true),
      Arguments.of(PAYMENT, T_ORDER, false),
      Arguments.of(PAYMENT, M_PRODUCT, false),
      Arguments.of(PRODUCT, T_ORDER, false),
    };
  }

  @ParameterizedTest(name = "{0} が {1} を書くと、通る: {2}")
  @MethodSource("writes")
  @DisplayName("モジュールは、自分のスキーマだけに書ける")
  void writesStayInOwnSchema(final String module, final Table<?> table, final boolean allowed) {
    final Consumer<DSLContext> write =
        tx -> tx.deleteFrom(table).where(DSL.falseCondition()).execute();

    if (allowed) {
      assertThatCode(() -> AsModule.run(dsl, module, write)).doesNotThrowAnyException();
    } else {
      assertPermissionDenied(() -> AsModule.run(dsl, module, write));
    }
  }

  @Test
  @DisplayName("ロールを切り替えないアプリロールは、業務のスキーマを読み書きできない")
  void appRoleCannotTouchBusinessSchemas() {
    assertPermissionDenied(() -> dsl.transaction(cfg -> cfg.dsl().fetchCount(M_PRODUCT)));
    assertPermissionDenied(() -> dsl.transaction(cfg -> cfg.dsl().fetchCount(T_ORDER)));
    assertPermissionDenied(() ->
        dsl.transaction(
            cfg -> cfg.dsl().deleteFrom(T_PAYMENT).where(DSL.falseCondition()).execute()));
  }

  @Test
  @DisplayName("アプリロールは modulith の表を読み書きできる")
  void appRoleCanUseModulithTables() {
    assertThatCode(() -> dsl.fetchCount(EVENT_PUBLICATION)).doesNotThrowAnyException();
    assertThatCode(() -> dsl.deleteFrom(EVENT_PUBLICATION).where(DSL.falseCondition()).execute())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("モジュールのロールは、イベントの出版の表へ INSERT だけできる")
  void moduleRolesOnlyInsertEventPublications() {
    final Consumer<DSLContext> select = tx -> tx.fetchCount(EVENT_PUBLICATION);
    final Consumer<DSLContext> update =
        tx ->
            tx.update(EVENT_PUBLICATION)
                .set(EVENT_PUBLICATION.STATUS, "x")
                .where(DSL.falseCondition())
                .execute();
    final Consumer<DSLContext> insert =
        tx ->
            tx.insertInto(EVENT_PUBLICATION)
                .set(EVENT_PUBLICATION.ID, java.util.UUID.randomUUID())
                .set(EVENT_PUBLICATION.LISTENER_ID, "isolation-test")
                .set(EVENT_PUBLICATION.EVENT_TYPE, "test.event")
                .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{}")
                .set(
                    EVENT_PUBLICATION.PUBLICATION_DATE,
                    java.time.Instant.parse("2026-11-18T00:00:00Z"))
                .execute();

    assertThatCode(() -> AsModule.run(dsl, ORDERING, insert)).doesNotThrowAnyException();
    assertPermissionDenied(() -> AsModule.run(dsl, ORDERING, select));
    assertPermissionDenied(() -> AsModule.run(dsl, ORDERING, update));
  }

  @Test
  @DisplayName("モジュールのロールの本体が失敗しても、呼び出し元のトランザクションはアプリロールへ戻る")
  void roleIsRestoredAfterFailedBody() {
    assertPermissionDenied(() -> AsModule.run(dsl, ORDERING, tx -> tx.fetchCount(T_PAYMENT)));

    assertThat(dsl.fetchValue("select current_user")).isEqualTo(appRole);
  }

  private static void assertPermissionDenied(final Runnable action) {
    final Throwable thrown = catchThrowable(action::run);

    assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
        .isInstanceOfSatisfying(
            SQLException.class,
            cause -> assertThat(cause.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
  }
}
