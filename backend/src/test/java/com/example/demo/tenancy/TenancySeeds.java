package com.example.demo.tenancy;

import static com.example.demo.jooq.tenancy.Tables.M_USER;
import static com.example.demo.jooq.tenancy.Tables.M_USER_IDENTITY;

import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import com.example.demo.shared.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * 開発用の代表データのテナントと所属を入れる。ローカルの Keycloak の利用者（{@code docker/keycloak/realm.json}）を、ログインの前でも所属させる。
 *
 * <p>test-user は 2 つのテナントの MEMBER、test-admin はサンプル商事だけの ADMIN にする。既にある行は入れ直さないので、何度呼んでも同じ状態になる。
 */
public final class TenancySeeds {

  /** realm.json の test-user の ID。Keycloak は ID トークンの {@code sub} にこの値を入れる。 */
  public static final String TEST_USER_SUBJECT = "d0037ab6-002f-4e5e-bcd1-c443fc748b82";

  /** realm.json の test-admin の ID。 */
  public static final String TEST_ADMIN_SUBJECT = "44d59939-e22a-45c4-be1d-6b96a96d86fc";

  /** test-user と test-admin が属するテナント。 */
  public static final UUID TRADING_TENANT = UUID.fromString("696ad4fa-fa01-478e-ae38-4f0394a01c94");

  /** test-user だけが属するテナント。 */
  public static final UUID GOODS_TENANT = UUID.fromString("dd4c8e16-e0d7-4496-b4d9-63a56877b50e");

  /** 代表データを入れるテナント。シーダーはこの順に入れる。 */
  public static final List<TenantId> TENANTS =
      List.of(new TenantId(TRADING_TENANT), new TenantId(GOODS_TENANT));

  /** シーダーが書く {@code *_pgm_cd}。 */
  private static final String PGM_CD = "tenancy.SeedLocalData";

  /** 利用者の共通カラムに登録する時刻。 */
  private static final Instant REGISTERED_AT = Instant.parse("2026-10-01T00:00:00Z");

  private TenancySeeds() {}

  /** tenancy のロールの {@code dsl} で、2 つのテナントと、test-user と test-admin の所属を入れる。 */
  public static void insert(final DSLContext dsl, final String issuer) {
    TestCommonColumns.runAs(
        PGM_CD,
        () -> {
          TestTenancy.insertTenant(dsl, TRADING_TENANT, "サンプル商事");
          TestTenancy.insertTenant(dsl, GOODS_TENANT, "サンプル物産");
          final String testUser = userOf(dsl, issuer, TEST_USER_SUBJECT);
          final String testAdmin = userOf(dsl, issuer, TEST_ADMIN_SUBJECT);
          TestTenancy.insertMembership(dsl, testUser, TRADING_TENANT, "MEMBER");
          TestTenancy.insertMembership(dsl, testUser, GOODS_TENANT, "MEMBER");
          TestTenancy.insertMembership(dsl, testAdmin, TRADING_TENANT, "ADMIN");
        });
  }

  /** IdP の識別に対応づけた利用者の ID を返す。まだログインしていなければ、利用者を作って対応づける。 */
  private static String userOf(final DSLContext dsl, final String issuer, final String subject) {
    final Optional<UUID> existing =
        dsl.select(M_USER.PUBLIC_ID)
            .from(M_USER)
            .join(M_USER_IDENTITY)
            .on(M_USER_IDENTITY.USER_ID.eq(M_USER.USER_ID))
            .where(M_USER_IDENTITY.ISSUER_URL.eq(issuer))
            .and(M_USER_IDENTITY.SUBJECT_CODE.eq(subject))
            .fetchOptional(M_USER.PUBLIC_ID);
    if (existing.isPresent()) {
      return existing.get().toString();
    }
    final UUID userId = UUID.randomUUID();
    final Long userPk =
        dsl.insertInto(M_USER)
            .set(M_USER.PUBLIC_ID, userId)
            .set(TestCommonColumns.at(REGISTERED_AT).forInsert(M_USER))
            .returning(M_USER.USER_ID)
            .fetchSingle(M_USER.USER_ID);
    dsl.insertInto(M_USER_IDENTITY)
        .set(M_USER_IDENTITY.USER_ID, userPk)
        .set(M_USER_IDENTITY.ISSUER_URL, issuer)
        .set(M_USER_IDENTITY.SUBJECT_CODE, subject)
        .set(TestCommonColumns.at(REGISTERED_AT).forInsert(M_USER_IDENTITY))
        .execute();
    return userId.toString();
  }
}
