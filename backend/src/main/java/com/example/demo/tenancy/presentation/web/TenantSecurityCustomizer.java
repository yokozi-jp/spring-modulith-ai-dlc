package com.example.demo.tenancy.presentation.web;

import com.example.demo.tenancy.TenancyQueries;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.stereotype.Component;

/**
 * アプリの Security filter chain に、テナントを選ぶ処理を足す。
 *
 * <p>Spring Security は、{@code Customizer<HttpSecurity>} の Bean を {@code SecurityConfig} が受け取る前の
 * {@link HttpSecurity} に適用する。認可の {@link AuthorizationFilter} の前で所属を引き直し、テナントを選ぶ前の業務の API を拒否する。
 * OIDC のログインの後には、所属が 1 つだけならそのテナントを選ぶ。選んだテナントの所属の API は、管理者だけに許す。
 *
 * <p>MDC にアプリの利用者の ID を入れる filter は、セッションの認証を読む {@link SecurityContextHolderFilter} の直後に置く。CSRF
 * の検証の失敗は {@code ExceptionTranslationFilter} を通らずに {@code CsrfFilter} が拒否するので、その前に入れないと、拒否のログに利用者の
 * ID が付かない。
 *
 * <p>この Bean は、アプリが作るすべての {@link HttpSecurity} に適用される。ブラウザのセッションでない chain（Client Credentials
 * など）を足すときは、その chain をこの処理から外す。
 */
@Component
class TenantSecurityCustomizer implements Customizer<HttpSecurity> {

  /** 利用者の所属を引く参照。 */
  private final TenancyQueries tenancyQueries;

  /** セッションで選んだテナント。 */
  private final TenantSession tenantSession;

  /** 依存を受け取る。 */
  /* package */ TenantSecurityCustomizer(
      final TenancyQueries tenancyQueries, final TenantSession tenantSession) {
    this.tenancyQueries = tenancyQueries;
    this.tenantSession = tenantSession;
  }

  @Override
  public void customize(final HttpSecurity http) {
    // SecurityConfig の anyRequest より先に登録され、先に一致する。ROLE_ADMIN は TenantSelectionFilter
    // が引き直した所属の役割から来る。
    http.authorizeHttpRequests(auth ->
            auth.requestMatchers(CurrentTenantMemberController.PATH + "/**").hasRole("ADMIN"))
        .addFilterAfter(new UserIdMdcFilter(), SecurityContextHolderFilter.class)
        .addFilterBefore(
            new TenantSelectionFilter(tenancyQueries, tenantSession), AuthorizationFilter.class)
        .oauth2Login(login ->
            login.successHandler(new TenantSelectionSuccessHandler(tenancyQueries, tenantSession)));
  }
}
