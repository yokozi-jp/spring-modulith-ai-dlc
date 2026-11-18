package com.example.demo.testkit;

import com.example.demo.shared.security.AppUserPrincipal;
import java.io.Serial;
import java.time.Instant;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;

/**
 * ログインのときに {@code tenancy} が返すのと同じく、{@link AppUserPrincipal} でもある {@link OidcUser} で認証する補助。
 *
 * <p>Spring Security Test の {@code oidcLogin()} の既定の利用者は {@link AppUserPrincipal}
 * でないため、書き込みで共通カラムが例外にする。
 */
public final class AppUsers {

  /** テストの利用者の、アプリの利用者の ID。 */
  public static final String USER_ID = "0b6f2c1e-4d3a-4e5f-8a7b-9c0d1e2f3a4b";

  /** ID トークンの時刻。 */
  private static final Instant ISSUED_AT = Instant.parse("2026-10-01T00:00:00Z");

  private AppUsers() {}

  /** {@link #oidcUser()} でログインした MockMvc のリクエストにする。 */
  public static OidcLoginRequestPostProcessor oidcLogin() {
    return SecurityMockMvcRequestPostProcessors.oidcLogin().oidcUser(oidcUser());
  }

  /** IdP の {@code sub} とは別に、アプリの利用者の ID {@link #USER_ID} を持つ利用者を返す。 */
  public static OidcUser oidcUser() {
    final OidcIdToken idToken =
        OidcIdToken.withTokenValue("id-token")
            .subject("idp-sub")
            .issuedAt(ISSUED_AT)
            .expiresAt(ISSUED_AT.plusSeconds(300))
            .build();
    return new FixedAppOidcUser(idToken);
  }

  /** 名前は IdP の {@code sub} のまま、{@link AppUserPrincipal#userId()} が {@link #USER_ID} を返す利用者。 */
  private static final class FixedAppOidcUser extends DefaultOidcUser implements AppUserPrincipal {

    @Serial private static final long serialVersionUID = 1L;

    private FixedAppOidcUser(final OidcIdToken idToken) {
      super(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken);
    }

    @Override
    public String userId() {
      return USER_ID;
    }
  }
}
