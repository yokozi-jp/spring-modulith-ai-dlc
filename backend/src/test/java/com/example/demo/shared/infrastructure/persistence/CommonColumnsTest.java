package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static com.example.demo.jooq.tables.FixtureWorkLogTable.FIXTURE_WORK_LOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.Field;
import org.jooq.Table;
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
// 共通カラムの規則ごとにテストを分けるため、メソッドの数の上限を外す。
@SuppressWarnings("PMD.TooManyMethods")
class CommonColumnsTest {

  /** 固定した現在時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-03T01:02:03.123456Z");

  /** 固定した現在時刻を返す Clock。 */
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** 現在のスパンの trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** 呼び出し中のユースケースとして束縛する pgm_cd。 */
  private static final String PGM_CD = "ordering.PlaceOrder";

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

    final Map<Field<?>, Object> values = inUseCase(() -> commonColumns.forInsert(FIXTURE_ITEM));

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
        inUseCase(() -> commonColumns.forInsert(FIXTURE_ITEM));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    final Map<Field<?>, Object> anonymous = inUseCase(() -> commonColumns.forUpdate(FIXTURE_ITEM));

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

    assertThatThrownBy(() -> inUseCase(() -> commonColumns.forInsert(FIXTURE_ITEM)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not an OIDC user");
  }

  @Test
  @DisplayName("現在のスパンがない、または trace ID がすべて 0 なら、登録を失敗させる")
  void missingTraceIsRejected() {
    final CommonColumns withoutSpan = new CommonColumns(CLOCK, TracerStubs.withoutSpan());
    final CommonColumns invalidTrace =
        new CommonColumns(CLOCK, TracerStubs.withTraceId("00000000000000000000000000000000"));

    assertThatThrownBy(() -> inUseCase(() -> withoutSpan.forInsert(FIXTURE_ITEM)))
        .as("スパンがない")
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no current trace");
    assertThatThrownBy(() -> inUseCase(() -> invalidTrace.forUpdate(FIXTURE_ITEM)))
        .as("無効な trace ID")
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no current trace");
  }

  @Test
  @DisplayName("UPDATE では更新のカラムだけを登録し、lock_no、作成、patched_* を含めない")
  void updateValuesHaveOnlyUpdatedColumns() {
    final Map<Field<?>, Object> values = inUseCase(() -> commonColumns.forUpdate(FIXTURE_ITEM));

    assertThat(values.keySet())
        .as("UPDATE の共通カラム（lock_no は TableWriter が書く）")
        .containsExactly(
            FIXTURE_ITEM.UPDATED_AT,
            FIXTURE_ITEM.UPDATED_BY,
            FIXTURE_ITEM.UPDATED_PGM_CD,
            FIXTURE_ITEM.UPDATED_TX_ID);
    assertThat(values.get(FIXTURE_ITEM.UPDATED_AT)).as("updated_at").isEqualTo(NOW);
    assertThat(values.get(FIXTURE_ITEM.UPDATED_TX_ID)).as("updated_tx_id").isEqualTo(TRACE_ID);
  }

  @Test
  @DisplayName("更新のカラムを省いたワークテーブルの INSERT では、作成のカラムと lock_no だけを登録する")
  void workTableInsertOmitsUpdatedColumns() {
    final Map<Field<?>, Object> values = inUseCase(() -> commonColumns.forInsert(FIXTURE_WORK_LOG));

    assertThat(values.keySet())
        .as("ワークテーブルの共通カラム")
        .containsExactly(
            FIXTURE_WORK_LOG.CREATED_AT,
            FIXTURE_WORK_LOG.CREATED_BY,
            FIXTURE_WORK_LOG.CREATED_PGM_CD,
            FIXTURE_WORK_LOG.CREATED_TX_ID,
            FIXTURE_WORK_LOG.LOCK_NO);
  }

  @Test
  @DisplayName("ワークテーブル以外で updated_at がなければ例外にする")
  void nonWorkTableWithoutUpdatedColumnsIsRejected() {
    final Table<?> renamed = FIXTURE_WORK_LOG.as("t_fixture_log");

    assertThatThrownBy(() -> inUseCase(() -> commonColumns.forInsert(renamed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("table=t_fixture_log");
  }

  @Test
  @DisplayName("CommandHandler と Listener の外で呼ばれ、pgm_cd が束縛されていなければ例外にする")
  void unboundPgmCdIsRejected() {
    assertThatThrownBy(() -> commonColumns.forInsert(FIXTURE_ITEM))
        .as("INSERT")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pgm_cd is not bound");
    assertThatThrownBy(() -> commonColumns.forUpdate(FIXTURE_ITEM))
        .as("UPDATE")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pgm_cd is not bound");
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

  @Test
  @DisplayName("共通カラムの 12 列は、forInsert と patched_*、ColumnValues、changeset の規則で同じである")
  void commonColumnNamesAgreeAcrossDefinitions() throws ReflectiveOperationException {
    final Set<String> written =
        Stream.concat(
                inUseCase(() -> commonColumns.forInsert(FIXTURE_ITEM)).keySet().stream(),
                Stream.of(
                    FIXTURE_ITEM.PATCHED_AT, FIXTURE_ITEM.PATCHED_BY, FIXTURE_ITEM.PATCHED_ID))
            .map(Field::getName)
            .collect(Collectors.toSet());
    final List<?> changesetRules =
        (List<?>)
            privateConstant(
                Class.forName(
                    "com.example.demo.persistence.conventions.ChangesetCommonColumnRules"),
                "COMMON_COLUMNS");

    assertThat(written).as("forInsert と patched_*").hasSize(12);
    assertThat(
            ((Set<?>) privateConstant(ColumnValues.class, "COMMON_COLUMNS"))
                .stream().map(String::valueOf))
        .as("ColumnValues が拒否する列")
        .containsExactlyInAnyOrderElementsOf(written);
    assertThat(
            changesetRules.stream().map(rule -> String.valueOf(((Map.Entry<?, ?>) rule).getKey())))
        .as("changeset の共通カラムの規則")
        .containsExactlyInAnyOrderElementsOf(written);
  }

  // 本番とテストの private 定数を、可視性を広げずに照らすため。
  @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
  private static Object privateConstant(final Class<?> owner, final String name)
      throws ReflectiveOperationException {
    final java.lang.reflect.Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(null);
  }

  /** ユースケースの呼び出しの中として、pgm_cd を束縛して実行する。 */
  private static <T> T inUseCase(final Supplier<T> call) {
    return ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).call(call::get);
  }
}
