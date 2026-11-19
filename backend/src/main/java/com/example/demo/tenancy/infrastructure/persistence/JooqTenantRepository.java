package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_TENANT;
import static com.example.demo.jooq.tenancy.Tables.M_USER;

import com.example.demo.shared.infrastructure.persistence.LockedRoot;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.tenancy.domain.model.Role;
import com.example.demo.tenancy.domain.model.Tenant;
import com.example.demo.tenancy.domain.model.TenantId;
import com.example.demo.tenancy.domain.model.TenantMember;
import com.example.demo.tenancy.domain.model.TenantRepository;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Records;
import org.jooq.Select;
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
    // ルートの行をロックした後に内部の主キーを読む。件数が 0 なら先に例外になるため、必ず 1 行ある。
    final Long tenantPk =
        dsl.select(M_TENANT.TENANT_ID).from(M_TENANT).where(byId).fetchSingle().value1();
    final Map<UUID, Role> saved =
        dsl.select(M_USER.PUBLIC_ID, M_MEMBERSHIP.ROLE_TYP)
            .from(M_MEMBERSHIP)
            .join(M_USER)
            .on(M_USER.USER_ID.eq(M_MEMBERSHIP.USER_ID))
            .where(M_MEMBERSHIP.TENANT_ID.eq(tenantPk))
            .fetchMap(M_USER.PUBLIC_ID, record -> Role.valueOf(record.get(M_MEMBERSHIP.ROLE_TYP)));
    final Set<UUID> kept =
        tenant.members().stream()
            .map(member -> member.userId().value())
            .collect(Collectors.toUnmodifiableSet());
    final List<UUID> removed =
        saved.keySet().stream().filter(userId -> !kept.contains(userId)).toList();
    if (!removed.isEmpty()) {
      root.deleteChildren(
          M_MEMBERSHIP,
          M_MEMBERSHIP.TENANT_ID.eq(tenantPk).and(M_MEMBERSHIP.USER_ID.in(userPks(removed))));
    }
    for (final TenantMember member : tenant.members()) {
      final UUID userId = member.userId().value();
      if (saved.containsKey(userId) && saved.get(userId) != member.role()) {
        root.updateChild(
            M_MEMBERSHIP,
            M_MEMBERSHIP.TENANT_ID.eq(tenantPk).and(M_MEMBERSHIP.USER_ID.eq(userPk(userId))),
            set -> set.set(M_MEMBERSHIP.ROLE_TYP, member.role().name()));
      }
    }
  }

  private Field<Long> userPk(final UUID userId) {
    return DSL.field(DSL.select(M_USER.USER_ID).from(M_USER).where(M_USER.PUBLIC_ID.eq(userId)));
  }

  private Select<Record1<Long>> userPks(final List<UUID> userIds) {
    return DSL.select(M_USER.USER_ID).from(M_USER).where(M_USER.PUBLIC_ID.in(userIds));
  }
}
