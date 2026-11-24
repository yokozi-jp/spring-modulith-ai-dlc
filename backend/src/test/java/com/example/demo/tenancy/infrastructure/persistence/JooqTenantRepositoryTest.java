package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.persistence.TableWriter;
import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TestTenancy;
import com.example.demo.tenancy.domain.model.Role;
import com.example.demo.tenancy.domain.model.Tenant;
import com.example.demo.tenancy.domain.model.TenantMember;
import com.example.demo.tenancy.domain.model.TenantRepository;
import com.example.demo.tenancy.domain.model.UserId;
import com.example.demo.testkit.DatabaseTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** テナントの所属の読み出しと、所属の変更の保存を検証する。 */
// 所属の保存の場合ごとにテストを分けるため、メソッド数の上限を外す。
@SuppressWarnings("PMD.TooManyMethods")
@DatabaseTest
class JooqTenantRepositoryTest {

  /** 管理者の役割のコード値。 */
  private static final String ADMIN = "ADMIN";

  /** 管理者でない利用者の役割のコード値。 */
  private static final String MEMBER = "MEMBER";

  /** 共通カラムに登録する時刻。 */
  private static final Instant UPDATED_AT = Instant.parse("2026-10-05T01:00:00.123456Z");

  /** テスト対象が使う jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("テナントを、所属した順の利用者と役割、ロック番号ごと読み戻せ、ないテナントは空を返す")
  void findsTenantWithMembersInJoinOrder() {
    final UUID tenantId = UUID.randomUUID();
    final String admin = joinNewUser(tenantId, ADMIN);
    final String member = joinNewUser(tenantId, MEMBER);

    final Tenant found = find(tenantId);

    assertThat(found.members())
        .containsExactly(
            new TenantMember(userId(admin), Role.ADMIN),
            new TenantMember(userId(member), Role.MEMBER));
    assertThat(found.lockNo()).isEqualTo(1L);
    assertThat(repository().findById(new TenantId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  @DisplayName("役割の変更と所属の取り外しを保存し、残る所属の行を更新して、テナントのロック番号を進める")
  void savesRoleChangeAndRemoval() {
    final UUID tenantId = UUID.randomUUID();
    final String first = joinNewUser(tenantId, ADMIN);
    final String second = joinNewUser(tenantId, MEMBER);
    final String third = joinNewUser(tenantId, MEMBER);
    final Tenant tenant = find(tenantId);
    tenant.changeRole(userId(second), Role.ADMIN);
    tenant.removeMember(userId(third));

    TestCommonColumns.runAs(() -> repository().update(tenant));

    final Tenant saved = find(tenantId);
    assertThat(saved.members())
        .containsExactly(
            new TenantMember(userId(first), Role.ADMIN),
            new TenantMember(userId(second), Role.ADMIN));
    assertThat(saved.lockNo()).isEqualTo(2L);
    assertThat(lockNoOf(first)).as("変えていない所属のロック番号（残る所属の行も更新する）").isEqualTo(2L);
    assertThat(lockNoOf(second)).as("役割を変えた所属のロック番号").isEqualTo(2L);
    assertThat(dsl.fetchExists(M_MEMBERSHIP, M_MEMBERSHIP.USER_ID.eq(userPk(third))))
        .as("取り外した利用者の所属の行 userId=%s", third)
        .isFalse();
    assertThat(dsl.fetchExists(M_USER, M_USER.PUBLIC_ID.eq(UUID.fromString(third))))
        .as("所属を外しても、利用者の行は残る userId=%s", third)
        .isTrue();
  }

  @Test
  @DisplayName("所属の利用者の行がないテナントは、SQL を実行する前に IllegalStateException にし、所属を変えない")
  void missingMemberUserIsRejectedBeforeWriting() {
    final UUID tenantId = UUID.randomUUID();
    final String admin = joinNewUser(tenantId, ADMIN);
    final Tenant tenant =
        Tenant.restore(
            new TenantId(tenantId),
            List.of(
                new TenantMember(userId(admin), Role.ADMIN),
                new TenantMember(userId(UUID.randomUUID().toString()), Role.MEMBER)),
            1L);

    assertThatThrownBy(() -> TestCommonColumns.runAs(() -> repository().update(tenant)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tenant member user not found");

    assertThat(find(tenantId).members()).hasSize(1);
    assertThat(find(tenantId).lockNo()).isEqualTo(1L);
  }

  @Test
  @DisplayName("ほかのテナントにも所属する利用者の所属を外しても、ほかのテナントの所属は残る")
  void removalKeepsTheMembershipOfAnotherTenant() {
    final UUID tenantId = UUID.randomUUID();
    final UUID otherTenantId = UUID.randomUUID();
    joinNewUser(tenantId, ADMIN);
    final String both = joinNewUser(tenantId, MEMBER);
    TestTenancy.member(dsl, both, otherTenantId, MEMBER);
    final Tenant tenant = find(tenantId);
    tenant.removeMember(userId(both));

    TestCommonColumns.runAs(() -> repository().update(tenant));

    assertThat(find(tenantId).members())
        .extracting(TenantMember::userId)
        .doesNotContain(userId(both));
    assertThat(find(otherTenantId).members())
        .containsExactly(new TenantMember(userId(both), Role.MEMBER));
    assertThat(find(otherTenantId).lockNo()).as("変えていないテナントのロック番号").isEqualTo(1L);
  }

  @Test
  @DisplayName("読んだ後にほかのリクエストがテナントを更新していると、種類が VERSION の ConflictException になり、所属は変わらない")
  void staleTenantBecomesConflict() {
    final UUID tenantId = UUID.randomUUID();
    final String admin = joinNewUser(tenantId, ADMIN);
    final String member = joinNewUser(tenantId, MEMBER);
    final Tenant stale = find(tenantId);
    final Tenant winner = find(tenantId);
    winner.changeRole(userId(member), Role.ADMIN);
    TestCommonColumns.runAs(() -> repository().update(winner));
    stale.removeMember(userId(member));

    assertThatThrownBy(() -> TestCommonColumns.runAs(() -> repository().update(stale)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            conflict ->
                assertThat(conflict.kind())
                    .as("tenantId=%s の古い更新の衝突の種類", tenantId)
                    .isEqualTo(ConflictException.Kind.VERSION));
    assertThat(find(tenantId).members())
        .containsExactly(
            new TenantMember(userId(admin), Role.ADMIN),
            new TenantMember(userId(member), Role.ADMIN));
  }

  /** 新しい利用者を作り、テナントに役割で所属させ、利用者の ID を返す。 */
  private String joinNewUser(final UUID tenantId, final String role) {
    final String userId = UUID.randomUUID().toString();
    TestTenancy.member(dsl, userId, tenantId, role);
    return userId;
  }

  private Tenant find(final UUID tenantId) {
    return repository()
        .findById(new TenantId(tenantId))
        .orElseThrow(() -> new AssertionError("テナントが見つからない: tenantId=" + tenantId));
  }

  private Long userPk(final String userId) {
    return dsl.select(M_USER.USER_ID)
        .from(M_USER)
        .where(M_USER.PUBLIC_ID.eq(UUID.fromString(userId)))
        .fetchSingle()
        .value1();
  }

  private Long lockNoOf(final String userId) {
    return dsl.select(M_MEMBERSHIP.LOCK_NO)
        .from(M_MEMBERSHIP)
        .where(M_MEMBERSHIP.USER_ID.eq(userPk(userId)))
        .fetchSingle()
        .value1();
  }

  private TenantRepository repository() {
    return new JooqTenantRepository(dsl, new TableWriter(dsl, TestCommonColumns.at(UPDATED_AT)));
  }

  private static UserId userId(final String userId) {
    return new UserId(UUID.fromString(userId));
  }
}
