package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.shared.security.TenantNotSelectedException;
import com.example.demo.shared.tenant.TenantContext;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import io.micrometer.common.KeyValue;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * セッションで選んだテナントの所属をリクエストのたびに引き直し、業務の API をテナントを選んだ後だけ通す。
 *
 * <p>所属が消えていれば、テナントの選択を外す。所属の役割が変わっていれば、所属の権限を作り直す。テナントを選ぶ前の利用者が業務の API を呼ぶと、{@link
 * TenantNotSelectedException} を投げる。所属の一覧とテナントを選ぶ API だけは、テナントを選ぶ前にも通す。
 *
 * <p>テナントを選んだ後のリクエストは、残りの処理の間だけ、選んだテナントを {@link TenantContext} に束縛する。テナントはセッションの権限だけから取り、リクエストの
 * header、パス、本文から取らない。
 *
 * <p>認証したリクエストは、所属の引き直しを含む残りの処理の間だけ、アプリの利用者の ID を MDC の {@value #USER_ID_ATTRIBUTE_KEY} に入れる。 利用者の
 * ID と束縛したテナントは、HTTP の受信の span の属性にも入れる。
 */
final class TenantSelectionFilter extends OncePerRequestFilter {

  /** テナントが決まっている必要がある API。 */
  private static final RequestMatcher REQUIRES_TENANT;

  /**
   * ログとトレースで、アプリの利用者の ID（{@link
   * AppUserPrincipal#userId()}）を持つ項目の名前（docs/observability/conventions.md）。
   */
  private static final String USER_ID_ATTRIBUTE_KEY = "user.id";

  static {
    final PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
    REQUIRES_TENANT =
        new AndRequestMatcher(
            paths.matcher("/api/**"),
            new NegatedRequestMatcher(
                new OrRequestMatcher(
                    paths.matcher(HttpMethod.GET, MembershipController.PATH),
                    paths.matcher(HttpMethod.PUT, CurrentTenantController.PATH))));
  }

  /** 匿名の認証を見分ける。 */
  private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

  /** このリクエストの認証を持つ。 */
  private final SecurityContextHolderStrategy holder =
      SecurityContextHolder.getContextHolderStrategy();

  /** 所属を引き直す参照。 */
  private final TenancyQueries tenancyQueries;

  /** セッションで選んだテナント。 */
  private final TenantSession tenantSession;

  /** 依存を受け取る。 */
  /* package */ TenantSelectionFilter(
      final TenancyQueries tenancyQueries, final TenantSession tenantSession) {
    super();
    this.tenancyQueries = tenancyQueries;
    this.tenantSession = tenantSession;
  }

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final @Nullable Authentication authentication = holder.getContext().getAuthentication();
    if (authentication == null || !trustResolver.isAuthenticated(authentication)) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!(authentication.getPrincipal() instanceof AppUserPrincipal user)) {
      doFilterInSelectedTenant(authentication, request, response, filterChain);
      return;
    }
    addToServerSpan(request, USER_ID_ATTRIBUTE_KEY, user.userId());
    try (MDC.MDCCloseable ignored = MDC.putCloseable(USER_ID_ATTRIBUTE_KEY, user.userId())) {
      doFilterInSelectedTenant(authentication, request, response, filterChain);
    }
  }

  /** 選んだテナントが残っていれば、束縛してリクエストの残りの処理を呼ぶ。 */
  private void doFilterInSelectedTenant(
      final Authentication authentication,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    final Optional<TenantId> tenantId = recheckedTenant(authentication, request, response);
    if (tenantId.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }
    addToServerSpan(request, TenantContext.ATTRIBUTE_KEY, tenantId.get().toString());
    doFilterInTenant(tenantId.get(), request, response, filterChain);
  }

  /** HTTP の受信の observation に、span の属性になる値を足す。 */
  private static void addToServerSpan(
      final HttpServletRequest request, final String key, final String value) {
    // 低い濃度の key value はメトリクスの tag にもなり、系列がテナントと利用者の数だけ増えるため、高い濃度にだけ入れる。
    ServerHttpObservationFilter.findObservationContext(request)
        .ifPresent(context -> context.addHighCardinalityKeyValue(KeyValue.of(key, value)));
  }

  /**
   * 認証したリクエストの、選んだテナントの所属を引き直して、残っているテナントを返す。選んでいないか所属が消えていれば空を返す。
   *
   * <p>引き直しは、所属が消えていればセッションの選択を外し、役割が変わっていれば権限を作り直す。
   *
   * @throws TenantNotSelectedException テナントの決まっている必要がある API を、テナントを選ぶ前に呼んだ場合
   */
  private Optional<TenantId> recheckedTenant(
      final Authentication authentication,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    final Optional<TenantId> tenant =
        TenantSession.selectedTenant(authentication)
            .flatMap(selected -> recheck(request, response, authentication, selected));
    if (tenant.isEmpty() && REQUIRES_TENANT.matches(request)) {
      throw new TenantNotSelectedException("tenant not selected");
    }
    return tenant;
  }

  /** 選んだテナントを束縛して、リクエストの残りの処理を呼ぶ。束縛はリクエストの処理が終わると外れる。 */
  // ScopedValue の処理が投げられる検査例外は 1 種類だけなので、IOException は包んで同じメソッドの外で戻す。
  // 戻すのは包んだ IOException そのもので、スタックトレースは残る。
  @SuppressWarnings({"PMD.ExceptionAsFlowControl", "PMD.PreserveStackTrace"})
  private static void doFilterInTenant(
      final TenantId tenantId,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {
    try {
      TenantContext.<@Nullable Object, ServletException>call(
          tenantId,
          () -> {
            try {
              filterChain.doFilter(request, response);
            } catch (IOException exception) {
              throw new CarriedIOException(exception);
            }
            return null;
          });
    } catch (CarriedIOException carried) {
      throw carried.ioException;
    }
  }

  /** 選んだテナントの所属を引き直し、消えていれば選択を外し、役割が変わっていれば権限を作り直す。所属が残っていればそのテナントを返す。 */
  private Optional<TenantId> recheck(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Authentication authentication,
      final TenantMembershipAuthority tenant) {
    final Optional<MembershipSummary> membership =
        authentication.getPrincipal() instanceof AppUserPrincipal user
            ? tenancyQueries.membership(user.userId(), tenant.tenantId())
            : Optional.empty();
    if (membership.isEmpty()) {
      tenantSession.clear(request, response, authentication);
      return Optional.empty();
    }
    if (!membership.get().role().equals(tenant.role())) {
      tenantSession.update(request, response, authentication, membership.get());
    }
    return Optional.of(new TenantId(UUID.fromString(tenant.tenantId())));
  }

  /**
   * {@link TenantContext#call} の処理から {@link IOException} を出すための包み。下流が投げる {@link
   * java.io.UncheckedIOException} とは区別する。
   */
  private static final class CarriedIOException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 包んだ例外。 */
    private final IOException ioException;

    /** 例外を包む。 */
    /* package */ CarriedIOException(final IOException ioException) {
      super(ioException);
      this.ioException = ioException;
    }
  }
}
