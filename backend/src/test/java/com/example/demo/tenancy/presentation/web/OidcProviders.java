package com.example.demo.tenancy.presentation.web;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.test.util.ReflectionTestUtils;

/** Security filter chain から OIDC の認証プロバイダーを取り出すテスト用の道具。 */
final class OidcProviders {

  private OidcProviders() {}

  /* package */ static OidcAuthorizationCodeAuthenticationProvider oidcProvider(
      final @Nullable Object manager) {
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
            .map(OidcAuthorizationCodeAuthenticationProvider.class::cast)
            .findFirst()
            .orElseThrow(() -> new AssertionError("OIDC の認証プロバイダーがない: manager=" + manager));
  }
}
