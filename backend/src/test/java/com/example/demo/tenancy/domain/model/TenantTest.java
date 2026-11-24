package com.example.demo.tenancy.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.tenancy.TenantId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** テナントの所属の変更が、管理者を 1 人以上残す規則と、所属にない利用者を拒む規則を守ることを検証する。 */
// 規則ごとに成功と拒否のケースを分けるため、メソッドが多い。メッセージの検証が同じ項目名を繰り返す。
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class TenantTest {

  /** 管理者の利用者。 */
  private static final UserId ADMIN = new UserId(UUID.randomUUID());

  /** 2 人目の管理者の利用者。 */
  private static final UserId SECOND_ADMIN = new UserId(UUID.randomUUID());

  /** 管理者でない利用者。 */
  private static final UserId MEMBER = new UserId(UUID.randomUUID());

  /** 所属していない利用者。 */
  private static final UserId OUTSIDER = new UserId(UUID.randomUUID());

  private static Tenant tenant(final TenantMember... members) {
    return Tenant.restore(new TenantId(UUID.randomUUID()), List.of(members), 3L);
  }

  @Test
  @DisplayName("管理者が 2 人いれば、一方の役割を MEMBER に下げられる")
  void demotesAnAdminWhenAnotherAdminRemains() {
    final Tenant tenant =
        tenant(new TenantMember(ADMIN, Role.ADMIN), new TenantMember(SECOND_ADMIN, Role.ADMIN));

    tenant.changeRole(ADMIN, Role.MEMBER);

    assertThat(tenant.members())
        .containsExactly(
            new TenantMember(ADMIN, Role.MEMBER), new TenantMember(SECOND_ADMIN, Role.ADMIN));
  }

  @Test
  @DisplayName("MEMBER を ADMIN に上げると、所属の順を変えずに役割だけが変わる")
  void promotesAMember() {
    final Tenant tenant =
        tenant(new TenantMember(ADMIN, Role.ADMIN), new TenantMember(MEMBER, Role.MEMBER));

    tenant.changeRole(MEMBER, Role.ADMIN);

    assertThat(tenant.members())
        .containsExactly(new TenantMember(ADMIN, Role.ADMIN), new TenantMember(MEMBER, Role.ADMIN));
  }

  @Test
  @DisplayName("最後の管理者の役割を MEMBER に下げると、BusinessRuleViolationException になり、所属は変わらない")
  void rejectsDemotingTheLastAdmin() {
    final Tenant tenant =
        tenant(new TenantMember(ADMIN, Role.ADMIN), new TenantMember(MEMBER, Role.MEMBER));

    assertThatThrownBy(() -> tenant.changeRole(ADMIN, Role.MEMBER))
        .isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageContaining("userId=" + ADMIN.value());
    assertThat(tenant.members())
        .containsExactly(
            new TenantMember(ADMIN, Role.ADMIN), new TenantMember(MEMBER, Role.MEMBER));
  }

  @Test
  @DisplayName("最後の管理者の役割を ADMIN のまま変える操作は、拒否しない")
  void keepingTheLastAdminAsAdminIsAllowed() {
    final Tenant tenant = tenant(new TenantMember(ADMIN, Role.ADMIN));

    tenant.changeRole(ADMIN, Role.ADMIN);

    assertThat(tenant.members()).containsExactly(new TenantMember(ADMIN, Role.ADMIN));
  }

  @Test
  @DisplayName("管理者が 2 人いれば、一方の所属を外せる")
  void removesAnAdminWhenAnotherAdminRemains() {
    final Tenant tenant =
        tenant(new TenantMember(ADMIN, Role.ADMIN), new TenantMember(SECOND_ADMIN, Role.ADMIN));

    tenant.removeMember(ADMIN);

    assertThat(tenant.members()).containsExactly(new TenantMember(SECOND_ADMIN, Role.ADMIN));
  }

  @Test
  @DisplayName("MEMBER の所属は、管理者が 1 人でも外せる")
  void removesAMember() {
    final Tenant tenant =
        tenant(new TenantMember(ADMIN, Role.ADMIN), new TenantMember(MEMBER, Role.MEMBER));

    tenant.removeMember(MEMBER);

    assertThat(tenant.members()).containsExactly(new TenantMember(ADMIN, Role.ADMIN));
  }

  @Test
  @DisplayName("最後の管理者の所属を外すと、BusinessRuleViolationException になり、所属は変わらない")
  void rejectsRemovingTheLastAdmin() {
    final Tenant tenant =
        tenant(new TenantMember(ADMIN, Role.ADMIN), new TenantMember(MEMBER, Role.MEMBER));

    assertThatThrownBy(() -> tenant.removeMember(ADMIN))
        .isInstanceOf(BusinessRuleViolationException.class)
        .hasMessageContaining("userId=" + ADMIN.value());
    assertThat(tenant.members()).hasSize(2);
  }

  @Test
  @DisplayName("所属していない利用者の役割の変更と所属の取り外しは、NotFoundException になる")
  void rejectsAUserWhoIsNotAMember() {
    final Tenant tenant = tenant(new TenantMember(ADMIN, Role.ADMIN));

    assertThatThrownBy(() -> tenant.changeRole(OUTSIDER, Role.MEMBER))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("userId=" + OUTSIDER.value());
    assertThatThrownBy(() -> tenant.removeMember(OUTSIDER))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("userId=" + OUTSIDER.value());
  }

  @Test
  @DisplayName("管理者のいないテナントでも、MEMBER の所属を外せる")
  void tenantWithoutAdminCanStillRemoveAMember() {
    final Tenant tenant = tenant(new TenantMember(MEMBER, Role.MEMBER));

    tenant.removeMember(MEMBER);

    assertThat(tenant.members()).isEmpty();
  }

  @Test
  @DisplayName("画面が読んだロック番号が保存済みと違うと、ConflictException になる")
  void ensureLockNoRejectsStaleLockNo() {
    final Tenant tenant = tenant(new TenantMember(ADMIN, Role.ADMIN));

    tenant.ensureLockNo(new ExpectedLockNo(3));
    assertThatThrownBy(() -> tenant.ensureLockNo(new ExpectedLockNo(2)))
        .isInstanceOf(ConflictException.class);
  }
}
