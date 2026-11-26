package com.example.demo.tenancy.presentation.web;

import static com.example.demo.jooq.tenancy.Tables.M_USER;
import static com.example.demo.jooq.tenancy.Tables.M_USER_IDENTITY;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.ContractCase;
import com.example.demo.testkit.SharedTestConfiguration;
import jakarta.servlet.http.Cookie;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * OIDC のログインから、所属を引いてテナントを選ぶまでと、セッションの期間を、実際の Security filter chain と DB のセッションで確かめる。
 *
 * <p>IdP のトークンの endpoint と ID トークンの検証だけを {@link FakeIdp} に差し替え、ログインのコールバックは本物の {@code
 * OAuth2LoginAuthenticationFilter} を通す。Cookie はブラウザと同じく応答の {@code Set-Cookie} を次のリクエストに送る。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({SharedTestConfiguration.class, TenantSelectionTest.FakeIdp.class})
@ExtendWith(CleanGeneratedTablesExtension.class)
// MockMvc の期待値は andExpect で書き、AssertJ の assert を使わないテストがある。
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
// ログインと Cookie とセッションの補助を、流れとセッションの期間を確かめるテストと同じクラスに置く。
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
class TenantSelectionTest {

  /** ID トークンの発行者。 */
  private static final String ISSUER = "https://issuer.example.test";

  /** ID トークンとアクセストークンの発行時刻。 */
  private static final Instant ISSUED_AT = Instant.parse("2026-10-01T00:00:00Z");

  /** 埋め込みサーバのない MockMvc での、Spring Session の Cookie の名前。 */
  private static final String SESSION_COOKIE = "SESSION";

  /** SPA が読む CSRF の Cookie の名前。 */
  private static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

  /** SPA が CSRF のトークンを送る header の名前。 */
  private static final String CSRF_HEADER = "X-XSRF-TOKEN";

  /** テナントを選ぶ前の 403 の problem type。 */
  private static final String TENANT_NOT_SELECTED = "/problems/tenant-not-selected";

  /** 所属していないテナントを選んだ 422 の problem type。 */
  private static final String NOT_TENANT_MEMBER = "/problems/tenancy/not-tenant-member";

  /** テナントを選んだ後に選び直した 422 の problem type。 */
  private static final String TENANT_ALREADY_SELECTED = "/problems/tenancy/tenant-already-selected";

  /** 選んだテナントの API。 */
  private static final String CURRENT_TENANT = "/api/current-tenant";

  /** テナントが決まっている必要がある業務の API。 */
  private static final String PRODUCTS = "/api/products";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** テナントと所属の行を書く jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** セッションが残っているかと、保存した認証を読む JDBC のリポジトリ。 */
  @Autowired private JdbcIndexedSessionRepository sessions;

  /** ログインが保存した OAuth2 の認可済みのクライアント。 */
  @Autowired private OAuth2AuthorizedClientRepository authorizedClients;

  /** セッションの行の作成の時刻を書き換え、期間の列を読む JDBC。 */
  @Autowired private JdbcTemplate jdbc;

  /** アプリの現在時刻。 */
  @Autowired private Clock clock;

  /** ブラウザが持つ Cookie。 */
  private final Map<String, String> cookies = new ConcurrentHashMap<>();

  @Test
  @DisplayName("所属のない利用者は、ログインの後も所属の一覧が空で、業務の API と選んだテナントの API は専用の type の 403 になる")
  @ContractCase(method = RequestMethod.GET, path = "/api/memberships", status = 200)
  void noMembershipLeavesTenantUnselected() throws Exception {
    login(newSubject());

    perform(get("/api/memberships"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
    perform(get(PRODUCTS))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
    perform(get(CURRENT_TENANT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
  }

  @Test
  @DisplayName("ログインの前に SPA が API を呼んで 401 になっても、ログインの後は API でなく / へ戻る")
  void loginAfterUnauthorizedApiCallReturnsToRoot() throws Exception {
    perform(get(CURRENT_TENANT)).andExpect(status().isUnauthorized());

    login(newSubject()).andExpect(header().string(HttpHeaders.LOCATION, "/"));
  }

  @Test
  @DisplayName("所属が 1 つの利用者は、ログインの後に選ばずにそのテナントへ入り、役割から ROLE_ADMIN を持つ")
  @ContractCase(method = RequestMethod.GET, path = CURRENT_TENANT, status = 200)
  void singleMembershipEntersTheTenantOnLogin() throws Exception {
    final String subject = newSubject();
    final String userId = signUp(subject);
    final UUID tenant = TestTenancy.tenant(dsl, "ひとつ商事");
    TestTenancy.join(dsl, userId, tenant, "ADMIN");

    login(subject);

    perform(get(CURRENT_TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenant.toString()))
        .andExpect(jsonPath("$.tenantName").value("ひとつ商事"));
    perform(get(PRODUCTS)).andExpect(status().isOk());
    assertThat(authorities(authenticationIn(sessionId())))
        .as("所属が 1 つの利用者のログインの後のセッションの権限")
        .contains("ROLE_ADMIN")
        .doesNotContain("ROLE_MEMBER");
  }

  @Test
  @DisplayName("所属が 2 つの利用者は、選ぶまで業務の API が 403 で、選ぶとそのテナントの MEMBER として業務の API を使える")
  @ContractCase(method = RequestMethod.PUT, path = CURRENT_TENANT, status = 204)
  void twoMembershipsRequireChoosingATenant() throws Exception {
    final TwoTenants user = loginWithTwoTenants();

    perform(get(PRODUCTS))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
    perform(get("/api/memberships"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].tenantName", contains("A 商事", "B 物産")))
        .andExpect(
            jsonPath(
                "$.items[*].tenantId",
                contains(user.first().toString(), user.second().toString())));

    select(user.second()).andExpect(status().isNoContent());

    perform(get(CURRENT_TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(user.second().toString()))
        .andExpect(jsonPath("$.tenantName").value("B 物産"));
    perform(get(PRODUCTS)).andExpect(status().isOk());
    assertThat(authorities(authenticationIn(sessionId())))
        .as("MEMBER の所属のテナントを選んだ後のセッションの権限")
        .contains("ROLE_MEMBER")
        .doesNotContain("ROLE_ADMIN");
  }

  @Test
  @DisplayName("テナントを選ぶとセッションの ID が変わり、選ぶ前の ID のセッションは残らず、その Cookie では 401 になる")
  void selectingATenantChangesTheSessionId() throws Exception {
    final TwoTenants user = loginWithTwoTenants();
    final String beforeSelection = sessionId();

    select(user.first()).andExpect(status().isNoContent());

    final String afterSelection = sessionId();
    assertThat(afterSelection).as("テナントを選んだ後のセッションの ID").isNotEqualTo(beforeSelection);
    assertThat(sessions.findById(beforeSelection))
        .as("選ぶ前の ID のセッション: sessionId=%s", beforeSelection)
        .isNull();
    mockMvc
        .perform(get(PRODUCTS).cookie(new Cookie(SESSION_COOKIE, encode(beforeSelection))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("セッションの ID を作り直しても CSRF の Cookie は変わらず、選ぶ前に受け取ったトークンで更新できる")
  void csrfTokenSurvivesTheSessionIdChange() throws Exception {
    final TwoTenants user = loginWithTwoTenants();
    final String tokenBeforeSelection = csrfToken();

    final MvcResult selection = select(user.first()).andExpect(status().isNoContent()).andReturn();

    assertThat(selection.getResponse().getCookie(CSRF_COOKIE))
        .as("テナントを選んだ応答の CSRF の Set-Cookie")
        .isNull();
    assertThat(cookies.get(CSRF_COOKIE))
        .as("テナントを選んだ後の CSRF の Cookie")
        .isEqualTo(tokenBeforeSelection);
    perform(post("/api/missing").header(CSRF_HEADER, tokenBeforeSelection))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("役割の権限を足して認証を作り直しても、ログインで保存した OAuth2 の認可済みのクライアントを同じ利用者の名前で引ける")
  void authorizedClientSurvivesTheRebuiltAuthentication() throws Exception {
    final TwoTenants user = loginWithTwoTenants();
    final Authentication loggedIn = authenticationIn(sessionId());

    select(user.first()).andExpect(status().isNoContent());

    final Authentication selected = authenticationIn(sessionId());
    assertThat(selected).as("テナントを選んだ後の認証").isNotEqualTo(loggedIn);
    assertThat(selected.getName()).as("テナントを選んだ後の認証の名前").isEqualTo(loggedIn.getName());
    final @Nullable OAuth2AuthorizedClient client =
        authorizedClients.loadAuthorizedClient("web", selected, new MockHttpServletRequest());
    assertThat(client).as("作り直した認証で引いた、認可済みのクライアント").isNotNull();
    assertThat(Objects.requireNonNull(client).getAccessToken().getTokenValue())
        .isEqualTo("access-token-" + user.subject());
  }

  @Test
  @DisplayName("所属を外すと、次のリクエストから業務の API と選んだテナントの API が専用の type の 403 になる")
  void removedMembershipIsRejectedOnTheNextRequest() throws Exception {
    final TwoTenants user = loginWithTwoTenants();
    select(user.first()).andExpect(status().isNoContent());
    perform(get(PRODUCTS)).andExpect(status().isOk());

    TestTenancy.leave(dsl, user.userId(), user.first());

    perform(get(PRODUCTS))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
    perform(get(CURRENT_TENANT))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
    perform(get("/api/memberships"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].tenantName", contains("B 物産")));
  }

  @Test
  @DisplayName("所属の役割を変えると、セッションの ID は変わらずに次のリクエストから新しい役割の権限になり、所属を外すと 403 になる")
  void changedRoleAppliesOnTheNextRequest() throws Exception {
    final TwoTenants user = loginWithTwoTenants();
    select(user.first()).andExpect(status().isNoContent());
    final String selectedSession = sessionId();

    TestTenancy.changeRole(dsl, user.userId(), user.first(), "ADMIN");
    perform(get(PRODUCTS)).andExpect(status().isOk());

    assertThat(sessionId()).as("役割が変わった後のセッションの ID").isEqualTo(selectedSession);
    assertThat(authorities(authenticationIn(sessionId())))
        .as("役割を ADMIN に変えた後のセッションの権限")
        .contains("ROLE_ADMIN")
        .doesNotContain("ROLE_MEMBER");
    perform(get(CURRENT_TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(user.first().toString()));

    TestTenancy.leave(dsl, user.userId(), user.first());

    perform(get(PRODUCTS))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
  }

  @Test
  @DisplayName("所属にないテナントと存在しないテナントは選べず 422 になり、テナントは決まらないままで、セッションの ID も変わらない")
  @ContractCase(method = RequestMethod.PUT, path = CURRENT_TENANT, status = 422)
  void tenantWithoutMembershipCannotBeSelected() throws Exception {
    loginWithTwoTenants();
    final UUID otherTenant = TestTenancy.tenant(dsl, "C 工業");
    final String beforeSelection = sessionId();

    select(otherTenant)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(NOT_TENANT_MEMBER));
    select(UUID.randomUUID())
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(NOT_TENANT_MEMBER));

    assertThat(sessionId()).as("選べなかった後のセッションの ID").isEqualTo(beforeSelection);
    perform(get(PRODUCTS))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
  }

  @Test
  @DisplayName("テナントを選んだ後は、別のテナントも同じテナントも選び直せず 422 になる")
  void selectedTenantCannotBeSwitchedWithoutSigningInAgain() throws Exception {
    final TwoTenants user = loginWithTwoTenants();
    select(user.first()).andExpect(status().isNoContent());

    select(user.second())
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TENANT_ALREADY_SELECTED));
    select(user.first())
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.type").value(TENANT_ALREADY_SELECTED));

    perform(get(CURRENT_TENANT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(user.first().toString()));
  }

  @Test
  @DisplayName("テナントの ID のない本文でテナントを選ぶと、入力検証エラーの 400 になる")
  @ContractCase(method = RequestMethod.PUT, path = CURRENT_TENANT, status = 400)
  void selectionWithoutTenantIdIsBadRequest() throws Exception {
    loginWithTwoTenants();

    perform(
            put(CURRENT_TENANT)
                .header(CSRF_HEADER, csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/validation-error"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/tenantId"));
  }

  /**
   * セッションの最長の期間と無操作の期間。
   *
   * <p>Spring Session は作成の時刻をアプリの Clock でなく自分の時計で決めるため、DB の行の作成の時刻を過去に書き換えて期間を過ぎさせる。
   */
  /* package */ @Nested
  class SessionLifetimeTest {

    /** 最長の期間（8 時間）を過ぎた、作ってからの経過時間。 */
    private static final Duration PAST_MAX_LIFETIME = Duration.ofHours(9);

    /** 最長の期間（8 時間）に満たない、作ってからの経過時間。 */
    private static final Duration WITHIN_MAX_LIFETIME = Duration.ofHours(7);

    @Test
    @DisplayName("最長の期間を過ぎたセッションは、直前に操作していても 401 になり、DB の行が消える")
    void sessionOlderThanMaxLifetimeIsInvalidated() throws Exception {
      login(newSubject());
      final String expired = sessionId();
      createdAgo(expired, PAST_MAX_LIFETIME);

      perform(get("/api/memberships")).andExpect(status().isUnauthorized());

      assertThat(rowCount(expired)).as("期間を過ぎたセッションの行: sessionId=%s", expired).isZero();
    }

    @Test
    @DisplayName("最長の期間に満たないセッションは、無操作の期間の中なら API を使える")
    void sessionWithinMaxLifetimeIsAccepted() throws Exception {
      login(newSubject());
      createdAgo(sessionId(), WITHIN_MAX_LIFETIME);

      perform(get("/api/memberships")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("テナントを選んでセッションの ID が変わっても作成の時刻は変わらず、最長の期間を過ぎると 401 になる")
    void selectingATenantKeepsTheCreationTime() throws Exception {
      final TwoTenants user = loginWithTwoTenants();
      final long createdAt = creationTime(sessionId());

      select(user.first()).andExpect(status().isNoContent());

      final String selected = sessionId();
      assertThat(creationTime(selected))
          .as("テナントを選んだ後のセッションの作成の時刻: sessionId=%s", selected)
          .isEqualTo(createdAt);
      createdAgo(selected, PAST_MAX_LIFETIME);
      perform(get(PRODUCTS)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("最長の期間を過ぎて 401 になった後にログインし直すと、作成の時刻が今の新しいセッションで API を使える")
    void loginAfterExpiryStartsANewSession() throws Exception {
      final String subject = newSubject();
      login(subject);
      final String expired = sessionId();
      createdAgo(expired, PAST_MAX_LIFETIME);
      perform(get("/api/memberships")).andExpect(status().isUnauthorized());
      final Instant beforeLogin = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);

      login(subject);

      final String renewed = sessionId();
      assertThat(renewed).as("ログインし直した後のセッションの ID").isNotEqualTo(expired);
      assertThat(Instant.ofEpochMilli(creationTime(renewed)))
          .as("ログインし直した後のセッションの作成の時刻: sessionId=%s", renewed)
          .isAfterOrEqualTo(beforeLogin);
      perform(get("/api/memberships")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ログインで作ったセッションの無操作の期間は 30 分である")
    void newSessionHasThirtyMinutesIdleTimeout() throws Exception {
      login(newSubject());
      final String id = sessionId();

      assertThat(
              jdbc.queryForObject(
                  "SELECT max_inactive_interval FROM modulith.spring_session WHERE session_id = ?",
                  Integer.class,
                  id))
          .as("セッションの max_inactive_interval（秒）: sessionId=%s", id)
          .isEqualTo(1800);
    }

    @Test
    @DisplayName("ログインの応答のセッションの Cookie は Max-Age を持たず、ブラウザを閉じると消える")
    void sessionCookieHasNoMaxAge() throws Exception {
      final MvcResult callback = login(newSubject()).andReturn();

      final Cookie cookie =
          Objects.requireNonNull(
              callback.getResponse().getCookie(SESSION_COOKIE), "ログインの応答にセッションの Cookie がない");
      assertThat(cookie.getMaxAge()).as("セッションの Cookie の Max-Age").isEqualTo(-1);
    }

    /** セッションの行の作成の時刻を、今から {@code age} 前にする。 */
    private void createdAgo(final String sessionId, final Duration age) {
      jdbc.update(
          "UPDATE modulith.spring_session SET creation_time = ? WHERE session_id = ?",
          Instant.now(clock).minus(age).toEpochMilli(),
          sessionId);
    }

    /** セッションの行の作成の時刻（エポックからのミリ秒）。 */
    private long creationTime(final String sessionId) {
      return Objects.requireNonNull(
          jdbc.queryForObject(
              "SELECT creation_time FROM modulith.spring_session WHERE session_id = ?",
              Long.class,
              sessionId),
          () -> "セッションの行がない: sessionId=" + sessionId);
    }

    /** セッションの ID の行の数。 */
    private int rowCount(final String sessionId) {
      return Objects.requireNonNull(
          jdbc.queryForObject(
              "SELECT count(*) FROM modulith.spring_session WHERE session_id = ?",
              Integer.class,
              sessionId));
    }
  }

  /** 2 つのテナント（A 商事、B 物産）の MEMBER の利用者でログインする。 */
  private TwoTenants loginWithTwoTenants() throws Exception {
    final String subject = newSubject();
    final String userId = signUp(subject);
    final UUID first = TestTenancy.tenant(dsl, "A 商事");
    final UUID second = TestTenancy.tenant(dsl, "B 物産");
    TestTenancy.join(dsl, userId, first, "MEMBER");
    TestTenancy.join(dsl, userId, second, "MEMBER");
    login(subject);
    return new TwoTenants(subject, userId, first, second);
  }

  /**
   * 2 つのテナントに属する利用者。
   *
   * @param subject ID トークンの {@code sub}
   * @param userId アプリの利用者の ID
   * @param first 名前の順で先のテナント
   * @param second 名前の順で後のテナント
   */
  private record TwoTenants(String subject, String userId, UUID first, UUID second) {}

  /** 一度ログインして利用者を作り、ブラウザの Cookie を捨てて、利用者の ID を返す。 */
  private String signUp(final String subject) throws Exception {
    login(subject);
    cookies.clear();
    return AsModule.call(
        dsl,
        "tenancy",
        tx ->
            tx.select(M_USER.PUBLIC_ID)
                .from(M_USER)
                .join(M_USER_IDENTITY)
                .on(M_USER_IDENTITY.USER_ID.eq(M_USER.USER_ID))
                .where(M_USER_IDENTITY.SUBJECT_CODE.eq(subject))
                .fetchSingle(M_USER.PUBLIC_ID)
                .toString());
  }

  /** 認可の開始からコールバックまでを送り、コールバックの応答を返す。 */
  private ResultActions login(final String subject) throws Exception {
    final String authorizationUri =
        Objects.requireNonNull(
            perform(get("/oauth2/authorization/web"))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION));
    final String state =
        UriUtils.decode(
            Objects.requireNonNull(
                UriComponentsBuilder.fromUriString(authorizationUri)
                    .build()
                    .getQueryParams()
                    .getFirst(OAuth2ParameterNames.STATE)),
            UTF_8);

    return perform(
            get("/login/oauth2/code/web")
                .param(OAuth2ParameterNames.CODE, subject)
                .param(OAuth2ParameterNames.STATE, state))
        .andExpect(status().isFound());
  }

  /** CSRF のトークンを付けて、テナントを選ぶ。 */
  private ResultActions select(final UUID tenantId) throws Exception {
    return perform(
        put(CURRENT_TENANT)
            .header(CSRF_HEADER, csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"tenantId\":\"" + tenantId + "\"}"));
  }

  /** CSRF の Cookie の値を返す。まだなければ、SPA と同じく更新の 403 で Cookie を受け取る。 */
  private String csrfToken() throws Exception {
    if (!cookies.containsKey(CSRF_COOKIE)) {
      perform(post("/api/missing")).andExpect(status().isForbidden());
    }
    return Objects.requireNonNull(cookies.get(CSRF_COOKIE), "CSRF の Cookie がない");
  }

  /** ブラウザの Cookie を付けて送り、応答の Cookie で持っている Cookie を更新する。 */
  private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
    cookies.forEach((name, value) -> request.cookie(new Cookie(name, value)));
    final ResultActions actions = mockMvc.perform(request);
    for (final Cookie cookie : actions.andReturn().getResponse().getCookies()) {
      if (cookie.getMaxAge() == 0) {
        cookies.remove(cookie.getName());
      } else {
        cookies.put(cookie.getName(), cookie.getValue());
      }
    }
    return actions;
  }

  /** ブラウザが持つセッションの Cookie が指すセッションの ID。 */
  private String sessionId() {
    return new String(
        Base64.getDecoder()
            .decode(Objects.requireNonNull(cookies.get(SESSION_COOKIE), "セッションの Cookie がない")),
        UTF_8);
  }

  /** DB のセッションに保存した認証。 */
  private Authentication authenticationIn(final String sessionId) {
    // JdbcIndexedSessionRepository のセッションの型は公開されていないため、Session として受ける。
    final Session session =
        Objects.requireNonNull(
            sessions.findById(sessionId), () -> "セッションがない: sessionId=" + sessionId);
    final SecurityContext context =
        Objects.requireNonNull(
            session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY),
            () -> "セッションに認証がない: sessionId=" + sessionId);
    return Objects.requireNonNull(context.getAuthentication(), "セッションに認証がない");
  }

  private static List<String> authorities(final Authentication authentication) {
    return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
  }

  private static String encode(final String sessionId) {
    return Base64.getEncoder().encodeToString(sessionId.getBytes(UTF_8));
  }

  private static String newSubject() {
    return "sub-" + UUID.randomUUID();
  }

  /**
   * IdP のトークンの endpoint と ID トークンの検証の代わり。
   *
   * <p>認可コードを ID トークンの {@code sub} にし、ID トークンの値に {@code sub} と nonce のハッシュを入れる。共有構成の登録は userinfo の
   * URL を持つが、{@code SignInOidcUserService} は UserInfo を読まないので、IdP を呼ばない。
   *
   * <p>{@code oauth2Login} は、この型の bean が 1 つのときだけそれを使う。本番の {@code OidcTokenClientConfig} の bean
   * と並ぶため、{@code @Primary} にする。
   */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class FakeIdp {

    /** 認可コードの代わりに受け取った {@code sub} の、ID トークンとアクセストークンを返す。 */
    @Bean
    @Primary
    /* package */ OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
        fakeTokenEndpoint() {
      return grant -> {
        final String subject =
            grant.getAuthorizationExchange().getAuthorizationResponse().getCode();
        final String nonce =
            Objects.requireNonNull(
                grant
                    .getAuthorizationExchange()
                    .getAuthorizationRequest()
                    .<String>getAttribute(OidcParameterNames.NONCE));
        return OAuth2AccessTokenResponse.withToken("access-token-" + subject)
            .tokenType(OAuth2AccessToken.TokenType.BEARER)
            .expiresIn(300)
            .scopes(Set.of("openid"))
            .additionalParameters(
                Map.of(OidcParameterNames.ID_TOKEN, subject + " " + nonceHash(nonce)))
            .build();
      };
    }

    /** {@link #fakeTokenEndpoint} の ID トークンの値を、署名を確かめずに claim へ戻す。 */
    @Bean
    /* package */ JwtDecoderFactory<ClientRegistration> fakeIdTokenDecoder() {
      return registration ->
          token -> {
            final String[] parts = token.split(" ", 2);
            return Jwt.withTokenValue(token)
                .header("alg", "none")
                .issuer(ISSUER)
                .subject(parts[0])
                .audience(List.of(registration.getClientId()))
                .issuedAt(ISSUED_AT)
                .expiresAt(ISSUED_AT.plusSeconds(300))
                .claim(IdTokenClaimNames.NONCE, parts[1])
                .build();
          };
    }

    /** OIDC Core 1.0 の nonce を、Spring Security と同じく SHA-256 の Base64url にする。 */
    private static String nonceHash(final String nonce) {
      try {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(US_ASCII)));
      } catch (final NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
