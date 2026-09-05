package com.example.demo;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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

  /** セキュリティフィルタチェーンを構築する。 */
  @Bean
  @SuppressWarnings("PMD.SignatureDeclareThrowsException")
  public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth
                    // ヘルスチェック用エンドポイント（liveness/readiness を含む）
                    .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                    .permitAll()
                    // API ドキュメント（OpenAPI JSON / Swagger UI）。
                    // 本番では springdoc.*.enabled=false でエンドポイント自体を無効化する前提。
                    .requestMatchers(
                        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                    .permitAll()
                    // 上記以外はすべて認証必須
                    .anyRequest()
                    .authenticated())
        // TODO 認証方式は暫定。OAuth2 ログイン（client 登録）を実装する段で置き換える。
        .httpBasic(Customizer.withDefaults());
    return http.build();
  }
}
