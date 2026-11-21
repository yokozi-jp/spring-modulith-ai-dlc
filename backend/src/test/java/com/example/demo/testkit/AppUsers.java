package com.example.demo.testkit;

import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.tenancy.TestTenancy;
import com.example.demo.tenancy.presentation.web.TenantMembershipAuthority;
import java.io.Serial;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * ログインのときに {@code tenancy} が返すのと同じく、{@link AppUserPrincipal} でもある {@link OidcUser} で認証する補助。
 *
 * <p>Spring Security Test の {@code oidcLogin()} の既定の利用者は {@link AppUserPrincipal}
 * でないため、書き込みで共通カラムが例外にする。
 */
public final class AppUsers {

  /** テストの利用者の、アプリの利用者の ID。 */
  public static final String USER_ID = "0b6f2c1e-4d3a-4e5f-8a7b-9c0d1e2f3a4b";

  /** テストの利用者が {@link #oidcLogin()} で選んだテナントの ID。 */
  public static final String TENANT_ID = "5d2e8f4a-1b3c-4d6e-9f70-8a1b2c3d4e5f";

  /** ID トークンの時刻。 */
  private static final Instant ISSUED_AT = Instant.parse("2026-10-01T00:00:00Z");

  /** OIDC のログインが付ける権限。 */
  private static final GrantedAuthority OIDC_USER = new SimpleGrantedAuthority("OIDC_USER");

  private AppUsers() {}

  /** {@link #TENANT_ID} の MEMBER として、テナントを選んだ後の MockMvc のリクエストにする（{@link #inTenant}）。 */
  public static RequestPostProcessor oidcLogin() {
    return inTenant(TENANT_ID, "MEMBER");
  }

  /**
   * {@link #USER_ID} の利用者をテナントに役割で所属させ、そのテナントを選んだ後の MockMvc のリクエストにする。
   *
   * <p>バックエンドはリクエストのたびに所属を引き直し、所属のない権限を外すため、リクエストを送る前に DB に所属を入れる。
   */
  public static RequestPostProcessor inTenant(final String tenantId, final String role) {
    return request -> {
      final DSLContext dsl =
          WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext())
              .getBean(DSLContext.class);
      TestTenancy.member(dsl, USER_ID, UUID.fromString(tenantId), role);
      return selectedTenant(tenantId, role).postProcessRequest(request);
    };
  }

  /**
   * {@link #USER_ID} の利用者が、役割の権限でテナントを選んだ後の MockMvc のリクエストにする。DB の所属は書かない。
   *
   * <p>リクエストの権限と、DB の所属の役割が食い違う状態を作るために使う。バックエンドは、リクエストのたびに所属を引き直し、食い違いを DB に合わせる。
   */
  public static RequestPostProcessor selectedTenant(final String tenantId, final String role) {
    return SecurityMockMvcRequestPostProcessors.oidcLogin()
        .oidcUser(oidcUser(List.of(OIDC_USER, new TenantMembershipAuthority(tenantId, role))));
  }

  /** IdP の {@code sub} とは別に、アプリの利用者の ID {@link #USER_ID} を持ち、テナントを選ぶ前の利用者を返す。 */
  public static OidcUser oidcUser() {
    return oidcUser(List.of(OIDC_USER));
  }

  private static OidcUser oidcUser(final Collection<GrantedAuthority> authorities) {
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .subject("idp-sub")
            .issuedAt(ISSUED_AT)
            .expiresAt(ISSUED_AT.plusSeconds(300))
            .build();
    return new FixedAppOidcUser(authorities, idToken);
  }

  /**
   * 本番の {@code AppOidcUser} と同じく、名前と {@link AppUserPrincipal#userId()} が {@link #USER_ID} を返す利用者。
   */
  private static final class FixedAppOidcUser extends DefaultOidcUser implements AppUserPrincipal {

    @Serial private static final long serialVersionUID = 1L;

    private FixedAppOidcUser(
        final Collection<GrantedAuthority> authorities, final OidcIdToken idToken) {
      super(authorities, idToken);
    }

    @Override
    public String getName() {
      return USER_ID;
    }

    @Override
    public String userId() {
      return USER_ID;
    }
  }
}
