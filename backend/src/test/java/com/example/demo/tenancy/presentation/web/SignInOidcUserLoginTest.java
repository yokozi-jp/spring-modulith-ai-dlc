package com.example.demo.tenancy.presentation.web;

import static com.example.demo.jooq.ordering.Tables.T_ORDER;
import static com.example.demo.jooq.tenancy.Tables.M_USER;
import static com.example.demo.jooq.tenancy.Tables.M_USER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.ordering.application.DraftOrderCommand;
import com.example.demo.ordering.application.DraftOrderCommandHandler;
import com.example.demo.product.TestProducts;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.BindTestTenantExtension;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;

/** IdP のログインから、アプリの利用者を引くか作り、書き込みの作成者に利用者の ID が入るまでを、実際の Spring の構成で検証する。 */
@SpringBootTest
@Import(SharedTestConfiguration.class)
@ExtendWith({CleanGeneratedTablesExtension.class, BindTestTenantExtension.class})
class SignInOidcUserLoginTest {

  /** 時刻を固定したトークンの発行時刻。 */
  private static final Instant ISSUED_AT = Instant.parse("2026-10-01T00:00:00Z");

  /** ログインの後の利用者を作るサービス。 */
  @Autowired private OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService;

  /** {@code OidcTokenClientConfig} の、タイムアウトを持つトークンのクライアント。 */
  @Autowired
  private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;

  /** 共有構成の OIDC クライアント登録。 */
  @Autowired private ClientRegistrationRepository registrations;

  /** 実際の Security filter chain。 */
  @Autowired private FilterChainProxy springSecurityFilterChain;

  /** 下書きの注文を作る、利用者の操作の書き込み。 */
  @Autowired private DraftOrderCommandHandler draftOrder;

  /** 行を読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("OIDC のログインは、このサービスで利用者を引き、タイムアウトを持つトークンのクライアントを使う")
  void oauth2LoginUsesTheServiceAndTheTokenClientWithTimeouts() {
    assertThat(oidcProviderFields("userService"))
        .as("OAuth2LoginAuthenticationFilter の OIDC の認証が使う OAuth2UserService")
        .containsExactly(oidcUserService);
    assertThat(oidcProviderFields("accessTokenResponseClient"))
        .as("OAuth2LoginAuthenticationFilter の OIDC の認証が使うトークンのクライアント")
        .containsExactly(tokenClient);
  }

  @Test
  @DisplayName("初めてのログインで利用者を作り、2 回目のログインで同じ利用者を引く")
  void firstLoginCreatesTheUserAndSecondLoginFindsIt() {
    final String subject = newSubject();

    final OidcUser first = login(subject);
    final OidcUser second = login(subject);

    final Optional<UUID> stored = userIdOf(subject);
    assertThat(stored).as("subject=%s の利用者", subject).isPresent();
    assertThat(first.getName())
        .isEqualTo(
            stored
                .orElseThrow(() -> new AssertionError("利用者が作られていない: subject=" + subject))
                .toString());
    assertThat(second.getName()).as("2 回目のログインの利用者 ID").isEqualTo(first.getName());
    final int identities =
        AsModule.call(
            dsl,
            "tenancy",
            tx -> tx.fetchCount(M_USER_IDENTITY, M_USER_IDENTITY.SUBJECT_CODE.eq(subject)));
    assertThat(identities).as("subject=%s の ID 連携の数", subject).isEqualTo(1);
  }

  @Test
  @DisplayName("subject が違えば、別の利用者になる")
  void differentSubjectsAreDifferentUsers() {
    assertThat(login(newSubject()).getName()).isNotEqualTo(login(newSubject()).getName());
  }

  @Test
  @DisplayName("ログインした利用者の書き込みは、作成者と更新者に sub でなく利用者の ID を登録する")
  void writesRegisterTheAppUserIdAsOperator() {
    final String subject = newSubject();
    final OidcUser user = login(subject);
    final UUID product = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    final String customerOrderCode = UniqueCodes.next("C");
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "web"));

    draftOrder.handle(
        new DraftOrderCommand(
            customerOrderCode, List.of(new DraftOrderCommand.Line(product.toString(), 1))));

    final List<String> operators =
        AsModule.call(
            dsl,
            "ordering",
            tx ->
                List.of(
                    tx.select(T_ORDER.CREATED_BY, T_ORDER.UPDATED_BY)
                        .from(T_ORDER)
                        .where(T_ORDER.CUSTOMER_ORDER_CODE.eq(customerOrderCode))
                        .fetchSingle()
                        .into(String[].class)));
    assertThat(operators)
        .as("customerOrderCode=%s の注文の作成者と更新者", customerOrderCode)
        .containsExactly(user.getName(), user.getName());
    assertThat(user.getName()).isNotEqualTo(subject);
  }

  @Test
  @DisplayName("別の利用者のセッションが残ったままの初めてのログインは、作成者と更新者に別の利用者でなく処理の名前を登録する")
  void firstLoginOverAnotherUsersSessionRegistersTheProcessAsOperator() {
    final OidcUser previous = login(newSubject());
    final OAuth2AuthenticationToken previousAuthentication =
        new OAuth2AuthenticationToken(previous, previous.getAuthorities(), "web");
    SecurityContextHolder.getContext().setAuthentication(previousAuthentication);
    final String subject = newSubject();

    login(subject);

    final List<String> operators =
        AsModule.call(
            dsl,
            "tenancy",
            tx ->
                List.of(
                    tx.select(M_USER.CREATED_BY, M_USER.UPDATED_BY)
                        .from(M_USER)
                        .join(M_USER_IDENTITY)
                        .on(M_USER_IDENTITY.USER_ID.eq(M_USER.USER_ID))
                        .where(M_USER_IDENTITY.SUBJECT_CODE.eq(subject))
                        .fetchSingle()
                        .into(String[].class)));
    assertThat(operators)
        .as("subject=%s の利用者の作成者と更新者", subject)
        .containsExactly("tenancy.SignInUser", "tenancy.SignInUser");
    assertThat(SecurityContextHolder.getContext().getAuthentication())
        .as("ログインの処理の後の、残っていたセッションの認証")
        .isSameAs(previousAuthentication);
  }

  /** ID トークンを受けた後の、アプリの利用者を作るサービスを呼ぶ。 */
  private OidcUser login(final String subject) {
    final ClientRegistration registration = registrations.findByRegistrationId("web");
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .issuer("https://issuer.example.test")
            .subject(subject)
            .audience(List.of(registration.getClientId()))
            .issuedAt(ISSUED_AT)
            .expiresAt(ISSUED_AT.plusSeconds(300))
            .build();
    final OAuth2AccessToken accessToken =
        new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            "access-token",
            ISSUED_AT,
            ISSUED_AT.plusSeconds(300),
            Set.of("openid"));
    return oidcUserService.loadUser(new OidcUserRequest(registration, accessToken, idToken));
  }

  private static String newSubject() {
    return "sub-" + UUID.randomUUID();
  }

  private Optional<UUID> userIdOf(final String subject) {
    return AsModule.call(
        dsl,
        "tenancy",
        tx ->
            tx.select(M_USER.PUBLIC_ID)
                .from(M_USER)
                .join(M_USER_IDENTITY)
                .on(M_USER_IDENTITY.USER_ID.eq(M_USER.USER_ID))
                .where(M_USER_IDENTITY.SUBJECT_CODE.eq(subject))
                .fetchOptional(M_USER.PUBLIC_ID));
  }

  /** OAuth2LoginAuthenticationFilter の OIDC の認証プロバイダーが持つ、{@code field} の値を、filter ごとに返す。 */
  private List<@Nullable Object> oidcProviderFields(final String field) {
    return springSecurityFilterChain.getFilterChains().stream()
        .flatMap(chain -> chain.getFilters().stream())
        .filter(OAuth2LoginAuthenticationFilter.class::isInstance)
        .map(filter -> ReflectionTestUtils.getField(filter, "authenticationManager"))
        .map(manager -> {
          Object current = manager;
          // 観測のための ObservationAuthenticationManager が ProviderManager を包んでいる。
          while (current != null && !(current instanceof ProviderManager)) {
            current = ReflectionTestUtils.getField(current, "delegate");
          }
          if (current == null) {
            throw new AssertionError("ProviderManager が見つからない: manager=" + manager);
          }
          return ((ProviderManager) current)
              .getProviders().stream()
                  .filter(OidcAuthorizationCodeAuthenticationProvider.class::isInstance)
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("OIDC の認証プロバイダーがない: manager=" + manager));
        })
        .map(provider -> ReflectionTestUtils.getField(provider, field))
        .toList();
  }
}
