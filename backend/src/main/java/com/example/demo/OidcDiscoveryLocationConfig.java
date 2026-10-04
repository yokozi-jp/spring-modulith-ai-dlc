package com.example.demo;

import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

/**
 * OIDC discovery を issuer と別の URL から読む設定（E2E の compose network 用、ADR-056）。
 *
 * <p>{@code oidc.discovery-uri}（環境変数 {@code OIDC_DISCOVERY_URI}）を設定したときだけ有効になり、Boot の自動構成の {@link
 * ClientRegistrationRepository} を置き換える。 未設定の環境では何も変わらないため、{@code application.yaml} には書かない（ADR-008 は
 * yaml の環境変数にデフォルトを置かない）。
 *
 * <p>discovery の {@code issuer} が {@code issuer-uri} と一致することを起動時に確かめ、{@link
 * ClientRegistrations#fromOidcConfiguration} で issuer と discovery の全体を registration に残す。 これにより ID
 * トークンの {@code iss} の検査と、{@code end_session_endpoint} を使う OIDC のログアウトが、discovery を issuer
 * から読む場合と同じに動く。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("oidc.discovery-uri")
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OidcDiscoveryLocationConfig {

  /** discovery を {@code oidc.discovery-uri} から読み、Boot の registration のプロパティを重ねる。 */
  @Bean
  public ClientRegistrationRepository clientRegistrationRepository(
      final OAuth2ClientProperties properties,
      @Value("${oidc.discovery-uri}") final String discoveryUri) {
    final Map<String, Object> configuration =
        Objects.requireNonNull(
            RestClient.create()
                .get()
                .uri(discoveryUri)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {}),
            () -> "OIDC discovery の応答が空である: " + discoveryUri);
    final ClientRegistration[] registrations =
        properties.getRegistration().entrySet().stream()
            .map(
                entry ->
                    toRegistration(entry.getKey(), entry.getValue(), properties, configuration))
            .toArray(ClientRegistration[]::new);
    return new InMemoryClientRegistrationRepository(registrations);
  }

  private static ClientRegistration toRegistration(
      final String registrationId,
      final OAuth2ClientProperties.Registration registration,
      final OAuth2ClientProperties properties,
      final Map<String, Object> configuration) {
    // Boot と同じく、provider を省略した registration は registration id を provider id とみなす。
    final String providerId =
        Objects.requireNonNullElse(registration.getProvider(), registrationId);
    final OAuth2ClientProperties.Provider provider =
        Objects.requireNonNull(
            properties.getProvider().get(providerId),
            () -> "provider " + providerId + " の設定が無い: registration=" + registrationId);
    final String expectedIssuer = provider.getIssuerUri();
    if (!Objects.equals(expectedIssuer, configuration.get("issuer"))) {
      throw new IllegalStateException(
          "OIDC discovery の issuer "
              + configuration.get("issuer")
              + " が provider "
              + providerId
              + " の issuer-uri "
              + expectedIssuer
              + " と一致しない");
    }
    return ClientRegistrations.fromOidcConfiguration(configuration)
        .registrationId(registrationId)
        .clientId(Objects.requireNonNull(registration.getClientId(), "client-id"))
        .clientSecret(registration.getClientSecret())
        .clientAuthenticationMethod(
            new ClientAuthenticationMethod(
                Objects.requireNonNull(
                    registration.getClientAuthenticationMethod(), "client-authentication-method")))
        .authorizationGrantType(
            new AuthorizationGrantType(
                Objects.requireNonNull(
                    registration.getAuthorizationGrantType(), "authorization-grant-type")))
        .redirectUri(registration.getRedirectUri())
        .scope(registration.getScope())
        .build();
  }
}
