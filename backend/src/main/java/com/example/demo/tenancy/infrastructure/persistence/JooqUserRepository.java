package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_USER;
import static com.example.demo.jooq.tenancy.Tables.M_USER_IDENTITY;

import com.example.demo.jooq.tenancy.tables.records.MUserIdentityRecord;
import com.example.demo.jooq.tenancy.tables.records.MUserRecord;
import com.example.demo.shared.infrastructure.persistence.AggregateTable;
import com.example.demo.shared.infrastructure.persistence.ChildTable;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.tenancy.domain.model.ExternalIdentity;
import com.example.demo.tenancy.domain.model.User;
import com.example.demo.tenancy.domain.model.UserId;
import com.example.demo.tenancy.domain.model.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** 利用者の集約を jOOQ で保存し、取り出す。 */
@Repository
class JooqUserRepository implements UserRepository {

  /** 利用者の IdP の識別。親の中の行は、発行者と識別子の組で同定する。 */
  private static final ChildTable<MUserRecord, MUserIdentityRecord> IDENTITIES =
      ChildTable.of(
          M_USER_IDENTITY,
          M_USER_IDENTITY.USER_ID,
          M_USER_IDENTITY.ISSUER_URL,
          M_USER_IDENTITY.SUBJECT_CODE);

  /** 利用者の集約。ルートは公開 ID で一つに決める。 */
  private static final AggregateTable<MUserRecord, UUID, User> USER =
      AggregateTable.of(M_USER, M_USER.PUBLIC_ID, M_USER.USER_ID, IDENTITIES);

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 利用者の集約を INSERT する shared の入口。 */
  private final TableWriter tableWriter;

  /** jOOQ のコンテキストと shared の入口を受け取る。 */
  /* package */ JooqUserRepository(final DSLContext dsl, final TableWriter tableWriter) {
    this.dsl = dsl;
    this.tableWriter = tableWriter;
  }

  @Override
  public Optional<User> findByIdentity(final ExternalIdentity identity) {
    return dsl.select(
            M_USER.PUBLIC_ID.convertFrom(UserId::new),
            DSL.multiset(
                    DSL.select(M_USER_IDENTITY.ISSUER_URL, M_USER_IDENTITY.SUBJECT_CODE)
                        .from(M_USER_IDENTITY)
                        .where(M_USER_IDENTITY.USER_ID.eq(M_USER.USER_ID))
                        .orderBy(M_USER_IDENTITY.USER_IDENTITY_ID))
                .convertFrom(identities -> identities.map(Records.mapping(ExternalIdentity::new))),
            M_USER.LOCK_NO)
        .from(M_USER)
        .where(
            DSL.exists(
                DSL.selectOne()
                    .from(M_USER_IDENTITY)
                    .where(M_USER_IDENTITY.USER_ID.eq(M_USER.USER_ID))
                    .and(M_USER_IDENTITY.ISSUER_URL.eq(identity.issuer()))
                    .and(M_USER_IDENTITY.SUBJECT_CODE.eq(identity.subject()))))
        .fetchOptional(Records.mapping(User::restore));
  }

  @Override
  public void add(final User user) {
    tableWriter.insert(
        USER,
        user.id().value(),
        set -> {},
        rows ->
            rows.put(
                IDENTITIES,
                user.identities(),
                (identity, set) ->
                    set.set(M_USER_IDENTITY.ISSUER_URL, identity.issuer())
                        .set(M_USER_IDENTITY.SUBJECT_CODE, identity.subject())));
  }
}
