package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.DemoApplication;
import com.example.demo.jooq.tables.FixtureWorkLogTable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/** 共通カラムへ登録する値の組み立てを検証する。 */
class CommonColumnsTest {

  /** 固定した現在時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-03T01:02:03.123456Z");

  /** 固定した現在時刻を返す Clock。 */
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** 現在のスパンの trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** このテストクラスから求まる pgm_cd。 */
  private static final String PGM_CD = "shared.CommonColumnsTest";

  /** 現在のスパンを持つ、検証対象の共通処理。 */
  private final CommonColumns commonColumns =
      new CommonColumns(CLOCK, TracerStubs.withTraceId(TRACE_ID));

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("利用者の操作の INSERT では、作成と更新のカラムに sub、pgm_cd、trace ID と lock_no = 1 を登録する")
  void insertValuesForOidcUser() {
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .subject("user-sub")
            .issuedAt(NOW)
            .expiresAt(NOW.plusSeconds(300))
            .build();
    final DefaultOidcUser user =
        new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "web"));

    final Map<Field<?>, Object> values =
        commonColumns.forInsert(FIXTURE_ITEM, CommonColumnsTest.class);

    assertThat(values)
        .as("INSERT の共通カラム")
        .containsExactly(
            Map.entry(FIXTURE_ITEM.CREATED_AT, NOW),
            Map.entry(FIXTURE_ITEM.CREATED_BY, "user-sub"),
            Map.entry(FIXTURE_ITEM.CREATED_PGM_CD, PGM_CD),
            Map.entry(FIXTURE_ITEM.CREATED_TX_ID, TRACE_ID),
            Map.entry(FIXTURE_ITEM.UPDATED_AT, NOW),
            Map.entry(FIXTURE_ITEM.UPDATED_BY, "user-sub"),
            Map.entry(FIXTURE_ITEM.UPDATED_PGM_CD, PGM_CD),
            Map.entry(FIXTURE_ITEM.UPDATED_TX_ID, TRACE_ID),
            Map.entry(FIXTURE_ITEM.LOCK_NO, 1L));
  }

  @Test
  @DisplayName("利用者の操作でない処理（認証なし、匿名）では、作成者と更新者に pgm_cd と同じ値を登録する")
  void operatorIsPgmCdForNonUserProcessing() {
    final Map<Field<?>, Object> withoutAuthentication =
        commonColumns.forInsert(FIXTURE_ITEM, CommonColumnsTest.class);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    final Map<Field<?>, Object> anonymous =
        commonColumns.forUpdate(FIXTURE_ITEM, CommonColumnsTest.class);

    assertThat(withoutAuthentication.get(FIXTURE_ITEM.CREATED_BY))
        .as("認証なしの created_by")
        .isEqualTo(PGM_CD);
    assertThat(withoutAuthentication.get(FIXTURE_ITEM.UPDATED_BY))
        .as("認証なしの updated_by")
        .isEqualTo(PGM_CD);
    assertThat(anonymous.get(FIXTURE_ITEM.UPDATED_BY)).as("匿名の updated_by").isEqualTo(PGM_CD);
  }

  @Test
  @DisplayName("OIDC の利用者でない認証では、作成者を決められないため例外にする")
  void otherAuthenticatedPrincipalIsRejected() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

    assertThatThrownBy(() -> commonColumns.forInsert(FIXTURE_ITEM, CommonColumnsTest.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not an OIDC user");
  }

  @Test
  @DisplayName("現在のスパンがない、または trace ID がすべて 0 なら、登録を失敗させる")
  void missingTraceIsRejected() {
    final CommonColumns withoutSpan = new CommonColumns(CLOCK, TracerStubs.withoutSpan());
    final CommonColumns invalidTrace =
        new CommonColumns(CLOCK, TracerStubs.withTraceId("00000000000000000000000000000000"));

    assertThatThrownBy(() -> withoutSpan.forInsert(FIXTURE_ITEM, CommonColumnsTest.class))
        .as("スパンがない")
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no current trace");
    assertThatThrownBy(() -> invalidTrace.forUpdate(FIXTURE_ITEM, CommonColumnsTest.class))
        .as("無効な trace ID")
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no current trace");
  }

  @Test
  @DisplayName("UPDATE では更新のカラムと lock_no + 1 だけを登録し、作成と patched_* を含めない")
  void updateValuesIncrementLockNo() {
    final Map<Field<?>, Object> values =
        commonColumns.forUpdate(FIXTURE_ITEM, CommonColumnsTest.class);

    assertThat(values.keySet())
        .as("UPDATE の共通カラム")
        .containsExactly(
            FIXTURE_ITEM.UPDATED_AT,
            FIXTURE_ITEM.UPDATED_BY,
            FIXTURE_ITEM.UPDATED_PGM_CD,
            FIXTURE_ITEM.UPDATED_TX_ID,
            FIXTURE_ITEM.LOCK_NO);
    assertThat(values.get(FIXTURE_ITEM.UPDATED_AT)).as("updated_at").isEqualTo(NOW);
    assertThat(values.get(FIXTURE_ITEM.UPDATED_TX_ID)).as("updated_tx_id").isEqualTo(TRACE_ID);
    final String sql = DSL.using(SQLDialect.POSTGRES).update(FIXTURE_ITEM).set(values).getSQL();
    assertThat(sql)
        .as("lock_no を DB の値から加算すること")
        .contains("\"lock_no\" = (\"fixture\".\"t_fixture_item\".\"lock_no\" + ?)");
  }

  @Test
  @DisplayName("更新のカラムを省いたワークテーブルの INSERT では、作成のカラムと lock_no だけを登録する")
  void workTableInsertOmitsUpdatedColumns() {
    final FixtureWorkLogTable workLog = FixtureWorkLogTable.FIXTURE_WORK_LOG;

    final Map<Field<?>, Object> values = commonColumns.forInsert(workLog, CommonColumnsTest.class);

    assertThat(values.keySet())
        .as("ワークテーブルの共通カラム")
        .containsExactly(
            workLog.CREATED_AT,
            workLog.CREATED_BY,
            workLog.CREATED_PGM_CD,
            workLog.CREATED_TX_ID,
            workLog.LOCK_NO);
  }

  @Test
  @DisplayName("ワークテーブル以外で updated_at がなければ例外にする")
  void nonWorkTableWithoutUpdatedColumnsIsRejected() {
    final Table<?> renamed = FixtureWorkLogTable.FIXTURE_WORK_LOG.as("t_fixture_log");

    assertThatThrownBy(() -> commonColumns.forInsert(renamed, CommonColumnsTest.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("table=t_fixture_log");
  }

  @Test
  @DisplayName("モジュールに属する名前付きのクラスでなければ、pgm_cd を決められないため例外にする")
  void useCaseOutsideModuleIsRejected() {
    final Object anonymous = new Object() {};

    assertThatThrownBy(() -> commonColumns.forInsert(FIXTURE_ITEM, String.class))
        .as("ベースパッケージの外")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> commonColumns.forInsert(FIXTURE_ITEM, DemoApplication.class))
        .as("ベースパッケージの直下")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> commonColumns.forInsert(FIXTURE_ITEM, anonymous.getClass()))
        .as("匿名クラス")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("カラムがない、または型が異なれば例外にする")
  void missingOrMistypedColumnIsRejected() {
    assertThatThrownBy(() -> CommonColumns.requiredField(FIXTURE_ITEM, "lock_no", Integer.class))
        .as("型が異なる")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=lock_no");
    assertThatThrownBy(() -> CommonColumns.requiredField(FIXTURE_ITEM, "no_such", Long.class))
        .as("カラムがない")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=no_such");
  }
}
