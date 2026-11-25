package archfixture.violating.ordering.presentation.web;

import archfixture.violating.shared.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.springframework.web.filter.OncePerRequestFilter;

/** 違反：tenantIsBoundOnlyByAllowedCallers（別のモジュールの filter が、メソッド参照で header のテナントを束縛する）。 */
final class HeaderTenantFilter extends OncePerRequestFilter {

  /** テナントを束縛する入口。 */
  private final BiFunction<String, Supplier<String>, String> bind = TenantContext::call;

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain) {
    response.setHeader("X-Bound", bind.apply(request.getHeader("X-Tenant"), () -> "bound"));
  }
}
