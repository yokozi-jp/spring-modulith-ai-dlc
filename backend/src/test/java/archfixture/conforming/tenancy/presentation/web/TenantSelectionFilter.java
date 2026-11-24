package archfixture.conforming.tenancy.presentation.web;

import archfixture.conforming.shared.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** 規約どおり：TenantContext.call を呼べる 2 つの入口のうち、リクエストの filter。 */
final class TenantSelectionFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain) {
    TenantContext.call("tenant-1", () -> "bound");
  }
}
