package com.example.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * アプリケーション全体のセキュリティ設定。
 *
 * <p>独自の {@link SecurityFilterChain} を定義すると Actuator の ManagementWebSecurityAutoConfiguration
 * が後退し、Actuator を含む全リクエストをこの Chain が制御する。
 *
 * <p>ALB、ECS、コンテナの healthcheck は liveness と readiness の probe だけを未認証で叩くため、この二つだけを認証不要にする。 {@code
 * /actuator/health} のルートとそれ以外の health 配下は、ログイン済みでも誰にも許可しない（ADR-061）。 probe のパスは明示しているため、{@code
 * management.endpoints.web.base-path} を変えたらここも直す。 拒否する側は {@link EndpointRequest#to}
 * でエンドポイントクラスから解決する。
 */
@Configuration
public class SecurityConfig {

  /** SPA が読む CSRF の Cookie の名前。frontend の src/lib/csrf.ts と ZAP の script と同じ値にする（ADR-066）。 */
  private static final String CSRF_COOKIE_NAME = "__Host-XSRF-TOKEN";

  /** CSRF の Cookie を __Host- の条件（Secure、Path=/、Domain なし）と SameSite=Lax で発行する。 */
  private static CookieCsrfTokenRepository csrfTokenRepository() {
    final CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    repository.setCookieName(CSRF_COOKIE_NAME);
    // __Host- は Path=/ を要求する。context path の既定に頼らず明示する。
    repository.setCookiePath("/");
    // ローカルの HTTP でも Secure を付ける。Chromium と Firefox は http://localhost の Secure の Cookie
    // を受け付けるが、Safari は受け付けない（ADR-066）。
    repository.setCookieCustomizer(cookie -> cookie.secure(true).sameSite("Lax"));
    return repository;
  }

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
      final OAuth2AuthorizationRequestResolver authorizationRequestResolver,
      @Qualifier("handlerExceptionResolver") final HandlerExceptionResolver handlerExceptionResolver)
      throws Exception {
    final OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
        new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
    final RequestMatcher apiRequests = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
    logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/logged-out");

    http.authorizeHttpRequests(
            auth ->
                auth
                    // ALB、ECS、コンテナの healthcheck が未認証で叩く probe だけを許可する。
                    .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    // ルートの集約やコンポーネント別の health は、ログイン済みでも拒否する。
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                    .denyAll()
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
        // API の認証・認可エラーも MVC と同じ Problem Details 変換へ集約する。
        .exceptionHandling(
            exceptions ->
                exceptions
                    .defaultAuthenticationEntryPointFor(
                        (request, response, exception) ->
                            resolveOrSendError(
                                handlerExceptionResolver,
                                request,
                                response,
                                exception,
                                HttpStatus.UNAUTHORIZED),
                        apiRequests)
                    .defaultAccessDeniedHandlerFor(
                        (request, response, exception) ->
                            resolveOrSendError(
                                handlerExceptionResolver,
                                request,
                                response,
                                exception,
                                HttpStatus.FORBIDDEN),
                        apiRequests))
        .headers(
            headers ->
                headers
                    .referrerPolicy(
                        referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .permissionsPolicyHeader(
                        permissions ->
                            permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
        // SPA が __Host-XSRF-TOKEN Cookie を読み、更新系リクエストの X-XSRF-TOKEN Header で送り返す。
        // spa() も repository を設定するため、その後で差し替える（逆の順では spa() が上書きする）。
        .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository()))
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

  /**
   * 例外を MVC の Problem Details 変換へ渡し、どの resolver も扱わなかったときは {@code status} で sendError する。
   *
   * <p>sendError にするとコンテナが {@code /error} へ転送し、{@code ApiErrorController} が同じ Problem Details
   * を返す。戻り値を捨てると何も書かれず、空の 200 が返る。
   */
  private static void resolveOrSendError(
      final HandlerExceptionResolver resolver,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Exception exception,
      final HttpStatus status)
      throws IOException {
    if (resolver.resolveException(request, response, null, exception) == null) {
      response.sendError(status.value());
    }
  }
}
