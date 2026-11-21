package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_TENANT;
import static com.example.demo.jooq.tenancy.Tables.M_USER;

import com.example.demo.shared.tenant.TenantId;
import com.example.demo.tenancy.domain.model.Membership;
import com.example.demo.tenancy.domain.model.MembershipRepository;
import com.example.demo.tenancy.domain.model.Role;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.Records;
import org.jooq.SelectOnConditionStep;
import org.springframework.stereotype.Repository;

/** 利用者の所属を jOOQ で取り出す。 */
@Repository
class JooqMembershipRepository implements MembershipRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** jOOQ のコンテキストを受け取る。 */
  /* package */ JooqMembershipRepository(final DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<Membership> findByUser(final UserId userId) {
    return selectMemberships()
        .where(M_USER.PUBLIC_ID.eq(userId.value()))
        .orderBy(M_TENANT.TENANT_NAME, M_TENANT.PUBLIC_ID)
        .fetch(Records.mapping(Membership::new));
  }

  @Override
  public Optional<Membership> findByUserAndTenant(final UserId userId, final TenantId tenantId) {
    return selectMemberships()
        .where(M_USER.PUBLIC_ID.eq(userId.value()))
        .and(M_TENANT.PUBLIC_ID.eq(tenantId.value()))
        .fetchOptional(Records.mapping(Membership::new));
  }

  private SelectOnConditionStep<Record3<TenantId, String, Role>> selectMemberships() {
    return dsl.select(
            M_TENANT.PUBLIC_ID.convertFrom(TenantId::new),
            M_TENANT.TENANT_NAME,
            M_MEMBERSHIP.ROLE_TYP.convertFrom(Role::valueOf))
        .from(M_MEMBERSHIP)
        .join(M_USER)
        .on(M_USER.USER_ID.eq(M_MEMBERSHIP.USER_ID))
        .join(M_TENANT)
        .on(M_TENANT.TENANT_ID.eq(M_MEMBERSHIP.TENANT_ID));
  }
}
