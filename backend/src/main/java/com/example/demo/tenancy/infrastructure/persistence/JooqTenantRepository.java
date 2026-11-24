package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_MEMBERSHIP;
import static com.example.demo.jooq.tenancy.Tables.M_TENANT;
import static com.example.demo.jooq.tenancy.Tables.M_USER;

import com.example.demo.jooq.tenancy.tables.records.MMembershipRecord;
import com.example.demo.jooq.tenancy.tables.records.MTenantRecord;
import com.example.demo.shared.persistence.AggregateTable;
import com.example.demo.shared.persistence.ChildTable;
import com.example.demo.shared.persistence.TableWriter;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.domain.model.Role;
import com.example.demo.tenancy.domain.model.Tenant;
import com.example.demo.tenancy.domain.model.TenantMember;
import com.example.demo.tenancy.domain.model.TenantRepository;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** テナントの集約を jOOQ で取り出し、所属の変更を保存する。 */
@Repository
class JooqTenantRepository implements TenantRepository {

  /** テナントの所属。親の中の行は、利用者の内部の主キーで同定する（所属は利用者とテナントの組が一意）。 */
  private static final ChildTable<MMembershipRecord> MEMBERSHIPS =
      ChildTable.of(M_MEMBERSHIP, M_MEMBERSHIP.TENANT_ID, M_MEMBERSHIP.USER_ID);

  /** テナントの集約。ルートは公開 ID で一つに決める。 */
  private static final AggregateTable<MTenantRecord, UUID, Tenant> TENANT =
      AggregateTable.of(
          M_TENANT,
          M_TENANT.PUBLIC_ID,
          tenant -> tenant.id().value(),
          M_TENANT.TENANT_ID,
          MEMBERSHIPS);

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** テナントの集約を UPDATE する shared の入口。 */
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
    // 所属だけの変更でも、ルートの版を進める。同じテナントの所属を同時に変える 2 つのリクエストの片方が、409 になる。
    // 所属は、保存後にあるべき全行として渡す。保存済みの所属との差分は TableWriter が、ルートの行をロックした後に取る。
    // 外す所属は、集約が読んだ所属ではなく、今の行との差で決める。所属を足す処理は、同じトランザクションで m_tenant.lock_no を進めること。
    // 進めないと、読んだ後に足された行をここで消す。M_MEMBERSHIP の INSERT は TableWriter だけが書け（ArchUnit H1）、
    // 別の集約が M_MEMBERSHIP を ChildTable かルートのテーブルとして宣言する場合だけ、この前提が破れる（AggregateDeclarationsTest
    // が検出する）。
    final Map<UUID, Long> userIds = internalUserIds(tenant);
    tableWriter.update(
        TENANT,
        tenant,
        set -> {},
        MEMBERSHIPS.rows(
            tenant.members(),
            (member, set) ->
                set.set(M_MEMBERSHIP.USER_ID, userId(userIds, member))
                    .set(M_MEMBERSHIP.ROLE_TYP, member.role().name())));
  }

  /** テナントの所属の利用者の、公開 ID から内部の主キーへの対応を引く。 */
  private Map<UUID, Long> internalUserIds(final Tenant tenant) {
    final List<UUID> publicIds =
        tenant.members().stream().map(member -> member.userId().value()).toList();
    return dsl.select(M_USER.PUBLIC_ID, M_USER.USER_ID)
        .from(M_USER)
        .where(M_USER.PUBLIC_ID.in(publicIds))
        .fetchMap(M_USER.PUBLIC_ID, M_USER.USER_ID);
  }

  /** 所属の利用者の内部の主キー。利用者の行がないのはデータの不整合で、SQL を実行する前に止める。 */
  private static long userId(final Map<UUID, Long> userIds, final TenantMember member) {
    final Long id = userIds.get(member.userId().value());
    if (id == null) {
      throw new IllegalStateException(
          "tenant member user not found: userId=" + member.userId().value());
    }
    return id;
  }
}
