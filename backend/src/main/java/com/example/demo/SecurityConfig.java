package com.example.demo;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * アプリケーション全体のセキュリティ設定。
 *
 * <p>独自の {@link SecurityFilterChain} を定義すると Actuator の ManagementWebSecurityAutoConfiguration
 * が後退し、Actuator を含む全リクエストをこの Chain が制御する。
 *
 * <p>ヘルスチェック用のプローブ（{@code /actuator/health}、liveness/readiness）は ALB/ECS が未認証で叩くため 認証不要にする。{@link
 * EndpointRequest#to} でエンドポイントクラスから解決するため、 {@code management.endpoints.web.base-path}
 * を変更してもここを直す必要はない。
 */
@Configuration
public class SecurityConfig {

  /** OAuth2 認可リクエストへ PKCE（S256）の challenge と verifier を追加する。 */
  @Bean
  public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
      final ClientRegistrationRepository clientRegistrationRepository) {
    final DefaultOAuth2AuthorizationRequestResolver resolver =
        new DefaultOAuth2AuthorizationRequestResolver(
            clientRegistrationRepository, "/oauth2/authorization");
    resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
    return resolver;
  }

  /** セキュリティフィルタチェーンを構築する。 */
  @Bean
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  public SecurityFilterChain securityFilterChain(
      final HttpSecurity http,
      final ClientRegistrationRepository clientRegistrationRepository,
      final OAuth2AuthorizationRequestResolver authorizationRequestResolver)
      throws Exception {
    final OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
        new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
    logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/actuator/health");

    http.authorizeHttpRequests(
            auth ->
                auth
                    // ヘルスチェック用エンドポイント（liveness/readiness を含む）
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                    .permitAll()
                    // OAuth2 の認可開始・コールバックとエラー表示。
                    .requestMatchers("/oauth2/**", "/login/**", "/error")
                    .permitAll()
                    // API ドキュメント（OpenAPI JSON / Swagger UI）。
                    // 本番では springdoc.*.enabled=false でエンドポイント自体を無効化する前提。
                    .requestMatchers(
                        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                    .permitAll()
                    // 上記以外はすべて認証必須
                    .anyRequest()
                    .authenticated())
        // SPA が XSRF-TOKEN Cookie を読み、更新系リクエストの X-XSRF-TOKEN Header で送り返す。
        .csrf(csrf -> csrf.spa())
        .oauth2Login(
            oauth2 ->
                oauth2.authorizationEndpoint(
                    authorization ->
                        authorization.authorizationRequestResolver(authorizationRequestResolver)))
        // アプリケーションセッションを破棄した後、IdP が対応していれば SSO セッションも終了する。
        // Spring Security の既定どおり POST /logout と CSRF トークンを要求する。
        .logout(
            logout ->
                logout
                    .logoutSuccessHandler(logoutSuccessHandler)
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("APP_SESSION"));
    return http.build();
  }
}
