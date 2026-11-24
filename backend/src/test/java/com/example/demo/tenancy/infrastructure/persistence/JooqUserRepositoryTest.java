package com.example.demo.tenancy.infrastructure.persistence;

import static com.example.demo.jooq.tenancy.Tables.M_USER;
import static com.example.demo.jooq.tenancy.Tables.M_USER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.persistence.CommonColumns;
import com.example.demo.shared.persistence.TableWriter;
import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.tenancy.domain.model.ExternalIdentity;
import com.example.demo.tenancy.domain.model.User;
import com.example.demo.tenancy.domain.model.UserRepository;
import com.example.demo.testkit.DatabaseTest;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 利用者の保存と、IdP の識別からの読み戻しを検証する。 */
@DatabaseTest
class JooqUserRepositoryTest {

  /** 保存した時刻。 */
  private static final Instant ADDED_AT = Instant.parse("2026-10-05T01:00:00.123456Z");

  /** 発行者。 */
  private static final String ISSUER = "https://idp.example.test/realms/demo";

  /** テスト対象が使う jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("保存した利用者を、IdP の識別から、ID、識別、ロック番号ごと読み戻せる")
  void savesAndFindsByIdentity() {
    final ExternalIdentity identity = identity();
    final User user = User.register(identity);
    TestCommonColumns.runAs(() -> repository().add(user));

    final User found =
        repository()
            .findByIdentity(identity)
            .orElseThrow(() -> new AssertionError("利用者が見つからない: identity=" + identity));

    assertThat(found.id()).isEqualTo(user.id());
    assertThat(found.identities()).containsExactly(identity);
    assertThat(found.lockNo()).isEqualTo(1L);
  }

  @Test
  @DisplayName("対応づけていない識別では、空を返す。発行者だけが同じ識別も別の識別として扱う")
  void unknownIdentityIsEmpty() {
    final ExternalIdentity identity = identity();
    TestCommonColumns.runAs(() -> repository().add(User.register(identity)));

    assertThat(repository().findByIdentity(identity("another-sub"))).isEmpty();
    assertThat(
            repository()
                .findByIdentity(
                    new ExternalIdentity("https://another.example.test", identity.subject())))
        .isEmpty();
  }

  @Test
  @DisplayName("同じ識別を別の利用者に対応づける保存は、種類が UNIQUE の ConflictException になる")
  void duplicateIdentityBecomesConflict() {
    final ExternalIdentity identity = identity();
    TestCommonColumns.runAs(() -> repository().add(User.register(identity)));
    final User duplicate = User.register(identity);

    assertThatThrownBy(() -> TestCommonColumns.runAs(() -> repository().add(duplicate)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            conflict ->
                assertThat(conflict.kind())
                    .as("identity=%s の 2 件目の保存の衝突の種類", identity)
                    .isEqualTo(ConflictException.Kind.UNIQUE));
  }

  @Test
  @DisplayName("利用者と識別の行に、共通カラムの時刻、処理の名前、ロック番号 1 を登録する")
  void registersCommonColumns() {
    final ExternalIdentity identity = identity();
    final User user = User.register(identity);
    TestCommonColumns.runAs(() -> repository().add(user));

    assertThat(dsl.fetchSingle(M_USER, M_USER.PUBLIC_ID.eq(user.id().value())))
        .as("利用者の行 userId=%s", user.id().value())
        .satisfies(row -> {
          assertThat(row.getCreatedAt()).isEqualTo(ADDED_AT);
          assertThat(row.getCreatedBy()).isEqualTo(TestCommonColumns.PGM_CD);
          assertThat(row.getLockNo()).isEqualTo(1L);
        });
    assertThat(
            dsl.fetchSingle(M_USER_IDENTITY, M_USER_IDENTITY.SUBJECT_CODE.eq(identity.subject())))
        .as("識別の行 identity=%s", identity)
        .satisfies(row -> {
          assertThat(row.getIssuerUrl()).isEqualTo(ISSUER);
          assertThat(row.getUpdatedAt()).isEqualTo(ADDED_AT);
          assertThat(row.getLockNo()).isEqualTo(1L);
        });
  }

  private UserRepository repository() {
    final CommonColumns commonColumns = TestCommonColumns.at(ADDED_AT);
    return new JooqUserRepository(dsl, new TableWriter(dsl, commonColumns));
  }

  private static ExternalIdentity identity() {
    return identity(UUID.randomUUID().toString());
  }

  private static ExternalIdentity identity(final String subject) {
    return new ExternalIdentity(ISSUER, subject);
  }
}
