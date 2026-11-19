package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_USER;
import static com.example.demo.jooq.tenancy.Tables.M_USER_IDENTITY;

import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.tenancy.domain.model.ExternalIdentity;
import com.example.demo.tenancy.domain.model.User;
import com.example.demo.tenancy.domain.model.UserId;
import com.example.demo.tenancy.domain.model.UserRepository;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** 利用者の集約を jOOQ で保存し、取り出す。 */
@Repository
class JooqUserRepository implements UserRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る shared の共通処理。 */
  private final CommonColumns commonColumns;

  /** 業務テーブルの INSERT を実行する shared の入口。 */
  private final TableWriter tableWriter;

  /** jOOQ のコンテキストと shared の共通処理を受け取る。 */
  /* package */ JooqUserRepository(
      final DSLContext dsl, final CommonColumns commonColumns, final TableWriter tableWriter) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
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
        dsl.insertInto(M_USER)
            .set(M_USER.PUBLIC_ID, user.id().value())
            .set(commonColumns.forInsert(M_USER)));
    // TableWriter.insert は RETURNING の値を返さないため、内部の主キーを読み直す。
    final Long userId =
        dsl.select(M_USER.USER_ID)
            .from(M_USER)
            .where(M_USER.PUBLIC_ID.eq(user.id().value()))
            .fetchSingle()
            .value1();
    for (final ExternalIdentity identity : user.identities()) {
      tableWriter.insert(
          dsl.insertInto(M_USER_IDENTITY)
              .set(M_USER_IDENTITY.USER_ID, userId)
              .set(M_USER_IDENTITY.ISSUER_URL, identity.issuer())
              .set(M_USER_IDENTITY.SUBJECT_CODE, identity.subject())
              .set(commonColumns.forInsert(M_USER_IDENTITY)));
    }
  }
}
