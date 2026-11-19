package com.example.demo.tenancy.presentation.web;

import com.example.demo.tenancy.MembershipSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * セッションで選んだテナントを、書き、読み、外す。
 *
 * <p>選んだテナントは、認証の {@link TenantMembershipAuthority} だけで表し、セッションに別の値を持たない。
 */
@Component
class TenantSession {

  /**
   * Spring Security の既定と同じく、リクエストの属性とセッションに認証を保存する。
   *
   * <p>chain の repository は {@code HttpSecurity} の build で作られ、{@code TenantSecurityCustomizer}
   * が呼ばれる時点ではまだないため、同じ構成をここで作る。
   */
  private final SecurityContextRepository securityContextRepository =
      new DelegatingSecurityContextRepository(
          new RequestAttributeSecurityContextRepository(),
          new HttpSessionSecurityContextRepository());

  /** このリクエストの認証を持つ。 */
  private final SecurityContextHolderStrategy holder =
      SecurityContextHolder.getContextHolderStrategy();

  /** 所属のテナントを選ぶ。セッションの ID を作り直してから、所属の権限を足した認証をセッションに保存する。 */
  /* package */ void select(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication,
      final MembershipSummary membership) {
    request.changeSessionId();
    update(request, response, authentication, membership);
  }

  /** 選んだテナントの所属の役割が変わったときに、所属の権限を作り直した認証をセッションに保存する。セッションの ID は変えない。 */
  /* package */ void update(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication,
      final MembershipSummary membership) {
    save(
        request,
        response,
        withTenant(
            authentication,
            new TenantMembershipAuthority(membership.tenantId(), membership.role())));
  }

  /** テナントの選択を外す。所属の権限を消した認証をセッションに保存する。 */
  /* package */ void clear(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication) {
    save(request, response, withTenant(authentication, null));
  }

  /** 認証が持つ、選んだテナントの所属の権限を返す。テナントを選んでいなければ空を返す。 */
  /* package */ static Optional<TenantMembershipAuthority> selectedTenant(
      final Authentication authentication) {
    return authentication.getAuthorities().stream()
        .filter(TenantMembershipAuthority.class::isInstance)
        .map(TenantMembershipAuthority.class::cast)
        .findFirst();
  }

  private void save(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication) {
    final SecurityContext context = holder.createEmptyContext();
    context.setAuthentication(authentication);
    holder.setContext(context);
    securityContextRepository.saveContext(context, request, response);
  }

  /** 所属の権限を、指定した 1 つに置き換えた認証を返す。{@code tenant} が null なら所属の権限を外す。 */
  private static Authentication withTenant(
      final Authentication authentication, final @Nullable TenantMembershipAuthority tenant) {
    return authentication.toBuilder()
        .authorities(authorities -> {
          authorities.removeIf(TenantMembershipAuthority.class::isInstance);
          if (tenant != null) {
            authorities.add(tenant);
          }
        })
        .build();
  }
}
