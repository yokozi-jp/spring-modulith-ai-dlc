package com.example.demo.tenancy.presentation.web;

import java.io.Serial;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * IdP の {@link OidcUser} と同じ ID トークン、userinfo、権限を持ち、名前（{@link #getName()}）だけがアプリの利用者の ID になる利用者。
 *
 * <p>共通カラムの {@code *_by} は、この名前を登録する。名前は IdP の claim から取らないため、IdP が送る値で書き換えられない。
 */
final class AppOidcUser extends DefaultOidcUser {

  @Serial private static final long serialVersionUID = 1L;

  /** アプリの利用者の ID。 */
  private final String userId;

  /** IdP の利用者と、アプリの利用者の ID から作る。 */
  /* package */ AppOidcUser(final OidcUser idpUser, final String userId) {
    super(idpUser.getAuthorities(), idpUser.getIdToken(), idpUser.getUserInfo());
    this.userId = userId;
  }

  @Override
  public String getName() {
    return userId;
  }

  @Override
  public boolean equals(final @Nullable Object other) {
    return other instanceof AppOidcUser user && userId.equals(user.userId) && super.equals(other);
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + userId.hashCode();
  }
}
