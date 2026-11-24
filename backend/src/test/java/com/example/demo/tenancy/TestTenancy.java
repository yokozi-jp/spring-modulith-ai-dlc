package com.example.demo.tenancy;

import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_TENANT;
import static com.example.demo.jooq.tenancy.Tables.M_USER;

import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.testkit.AsModule;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * テナントと所属の行をテストで登録する補助。テナントと所属を作る操作はまだないため、tenancy のロールで DSLContext から直接書く。
 *
 * <p>利用者は、ログイン（{@code SignInOidcUserService}）か {@link TenancySeeds} が作る。ID を決めた利用者は {@link #member}
 * が作る。
 */
// テストの補助であり、テストケースを持たない。
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestTenancy {

  /** 共通カラムに登録する時刻。 */
  private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T00:00:00Z");

  /** 行を持つモジュール。 */
  private static final String TENANCY = "tenancy";

  private TestTenancy() {}

  /** テナントを登録し、テナント ID を返す。 */
  public static UUID tenant(final DSLContext dsl, final String tenantName) {
    final UUID tenantId = UUID.randomUUID();
    AsModule.run(
        dsl, TENANCY, tx -> TestCommonColumns.runAs(() -> insertTenant(tx, tenantId, tenantName)));
    return tenantId;
  }

  /** 利用者を、役割（ADMIN か MEMBER）を指定してテナントに所属させる。 */
  public static void join(
      final DSLContext dsl, final String userId, final UUID tenantId, final String role) {
    AsModule.run(
        dsl,
        TENANCY,
        tx -> TestCommonColumns.runAs(() -> insertMembership(tx, userId, tenantId, role)));
  }

  /** ID を決めた利用者を、テナントに役割で所属させる。利用者、テナント、所属のどれも、既にあれば作り直さず、所属の役割だけを {@code role} に合わせる。 */
  public static void member(
      final DSLContext dsl, final String userId, final UUID tenantId, final String role) {
    AsModule.run(
        dsl,
        TENANCY,
        tx ->
            TestCommonColumns.runAs(() -> {
              tx.insertInto(M_USER)
                  .set(M_USER.PUBLIC_ID, UUID.fromString(userId))
                  .set(TestCommonColumns.at(REGISTERED_AT).forInsert(M_USER))
                  .onConflict(M_USER.PUBLIC_ID)
                  .doNothing()
                  .execute();
              insertTenant(tx, tenantId, "テストのテナント");
              insertMembership(tx, userId, tenantId, role);
            }));
    changeRole(dsl, userId, tenantId, role);
  }

  /** 利用者の、テナントでの役割を変える。 */
  public static void changeRole(
      final DSLContext dsl, final String userId, final UUID tenantId, final String role) {
    AsModule.run(
        dsl,
        TENANCY,
        tx ->
            tx.update(M_MEMBERSHIP)
                .set(M_MEMBERSHIP.ROLE_TYP, role)
                .where(M_MEMBERSHIP.USER_ID.eq(userPk(tx, userId)))
                .and(M_MEMBERSHIP.TENANT_ID.eq(tenantPk(tx, tenantId)))
                .execute());
  }

  /** 利用者の、テナントへの所属を消す。 */
  public static void leave(final DSLContext dsl, final String userId, final UUID tenantId) {
    AsModule.run(
        dsl,
        TENANCY,
        tx ->
            tx.deleteFrom(M_MEMBERSHIP)
                .where(M_MEMBERSHIP.USER_ID.eq(userPk(tx, userId)))
                .and(M_MEMBERSHIP.TENANT_ID.eq(tenantPk(tx, tenantId)))
                .execute());
  }

  /**
   * テナントの行を 1 件登録する。既に同じ ID の行があれば何もしない。{@code *_pgm_cd} は束縛しないので、呼ぶ側が {@link
   * TestCommonColumns#runAs} の中で、tenancy のロールで呼ぶ。
   */
  /* package */ static void insertTenant(
      final DSLContext dsl, final UUID tenantId, final String tenantName) {
    dsl.insertInto(M_TENANT)
        .set(M_TENANT.PUBLIC_ID, tenantId)
        .set(M_TENANT.TENANT_NAME, tenantName)
        .set(TestCommonColumns.at(REGISTERED_AT).forInsert(M_TENANT))
        .onConflict(M_TENANT.PUBLIC_ID)
        .doNothing()
        .execute();
  }

  /**
   * 所属の行を 1 件登録する。既に同じ利用者とテナントの行があれば何もしない。{@code *_pgm_cd} は束縛しないので、呼ぶ側が {@link
   * TestCommonColumns#runAs} の中で、tenancy のロールで呼ぶ。
   */
  /* package */ static void insertMembership(
      final DSLContext dsl, final String userId, final UUID tenantId, final String role) {
    dsl.insertInto(M_MEMBERSHIP)
        .set(M_MEMBERSHIP.USER_ID, userPk(dsl, userId))
        .set(M_MEMBERSHIP.TENANT_ID, tenantPk(dsl, tenantId))
        .set(M_MEMBERSHIP.ROLE_TYP, role)
        .set(TestCommonColumns.at(REGISTERED_AT).forInsert(M_MEMBERSHIP))
        .onConflict(M_MEMBERSHIP.USER_ID, M_MEMBERSHIP.TENANT_ID)
        .doNothing()
        .execute();
  }

  private static Field<Long> userPk(final DSLContext dsl, final String userId) {
    return DSL.field(
        dsl.select(M_USER.USER_ID)
            .from(M_USER)
            .where(M_USER.PUBLIC_ID.eq(UUID.fromString(userId))));
  }

  private static Field<Long> tenantPk(final DSLContext dsl, final UUID tenantId) {
    return DSL.field(
        dsl.select(M_TENANT.TENANT_ID).from(M_TENANT).where(M_TENANT.PUBLIC_ID.eq(tenantId)));
  }
}
