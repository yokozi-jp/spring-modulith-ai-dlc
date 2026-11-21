package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_TENANT;
import static com.example.demo.jooq.tenancy.Tables.M_USER;

import com.example.demo.shared.infrastructure.persistence.LockedRoot;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.tenancy.domain.model.Role;
import com.example.demo.tenancy.domain.model.Tenant;
import com.example.demo.tenancy.domain.model.TenantMember;
import com.example.demo.tenancy.domain.model.TenantRepository;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** テナントの集約を jOOQ で取り出し、所属の変更を保存する。 */
@Repository
class JooqTenantRepository implements TenantRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 業務テーブルの UPDATE と DELETE を書く shared の入口。 */
  private final TableWriter tableWriter;

  /** jOOQ のコンテキストと shared の入口を受け取る。 */
  /* package */ JooqTenantRepository(final DSLContext dsl, final TableWriter tableWriter) {
    this.dsl = dsl;
    this.tableWriter = tableWriter;
  }

  @Override
  public Optional<Tenant> findById(final TenantId id) {
    return dsl.select(
            M_TENANT.PUBLIC_ID.convertFrom(TenantId::new),
            DSL.multiset(
                    DSL.select(
                            M_USER.PUBLIC_ID.convertFrom(UserId::new),
                            M_MEMBERSHIP.ROLE_TYP.convertFrom(Role::valueOf))
                        .from(M_MEMBERSHIP)
                        .join(M_USER)
                        .on(M_USER.USER_ID.eq(M_MEMBERSHIP.USER_ID))
                        .where(M_MEMBERSHIP.TENANT_ID.eq(M_TENANT.TENANT_ID))
                        .orderBy(M_MEMBERSHIP.MEMBERSHIP_ID))
                .convertFrom(members -> members.map(Records.mapping(TenantMember::new))),
            M_TENANT.LOCK_NO)
        .from(M_TENANT)
        .where(M_TENANT.PUBLIC_ID.eq(id.value()))
        .fetchOptional(Records.mapping(Tenant::restore));
  }

  @Override
  public void update(final Tenant tenant) {
    final Condition byId = M_TENANT.PUBLIC_ID.eq(tenant.id().value());
    // 所属だけの変更でも、ルートの版を進める。同じテナントの所属を同時に変える 2 つのリクエストの片方が、409 になる。
    final LockedRoot root =
        tableWriter.updateCheckingVersion(M_TENANT, byId, tenant.lockNo(), set -> {});
    // ルートの行をロックした後に保存済みの所属を読む。
    // ponytail: 外す所属は、集約が読んだ所属ではなく、今の行との差で決める。所属を足す処理は、同じ
    // トランザクションで m_tenant.lock_no を進めること。進めないと、読んだ後に足された行をここで消す。
    final Map<UUID, SavedMembership> saved =
        dsl.select(M_USER.PUBLIC_ID, M_MEMBERSHIP.MEMBERSHIP_ID, M_MEMBERSHIP.ROLE_TYP)
            .from(M_MEMBERSHIP)
            .join(M_TENANT)
            .on(M_TENANT.TENANT_ID.eq(M_MEMBERSHIP.TENANT_ID))
            .join(M_USER)
            .on(M_USER.USER_ID.eq(M_MEMBERSHIP.USER_ID))
            .where(byId)
            .fetchMap(
                M_USER.PUBLIC_ID,
                record ->
                    new SavedMembership(
                        record.get(M_MEMBERSHIP.MEMBERSHIP_ID),
                        Role.valueOf(record.get(M_MEMBERSHIP.ROLE_TYP))));
    final Map<UUID, Role> next =
        tenant.members().stream()
            .collect(Collectors.toUnmodifiableMap(m -> m.userId().value(), TenantMember::role));
    final List<Long> removed =
        saved.entrySet().stream()
            .filter(entry -> !next.containsKey(entry.getKey()))
            .map(entry -> entry.getValue().membershipId())
            .toList();
    if (!removed.isEmpty()) {
      root.deleteChildren(M_MEMBERSHIP, M_MEMBERSHIP.MEMBERSHIP_ID.in(removed));
    }
    saved.forEach((userId, membership) -> {
      final Role role = next.get(userId);
      if (role != null && role != membership.role()) {
        root.updateChild(
            M_MEMBERSHIP,
            M_MEMBERSHIP.MEMBERSHIP_ID.eq(membership.membershipId()),
            set -> set.set(M_MEMBERSHIP.ROLE_TYP, role.name()));
      }
    });
  }

  /** 保存済みの所属の主キーと役割。 */
  private record SavedMembership(long membershipId, Role role) {}
}
