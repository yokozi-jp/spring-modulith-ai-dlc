package com.example.demo.tenancy.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.shared.tenant.TenantContext;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import com.example.demo.tenancy.TenantMembersDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@link TenantSelectionFilter} が、選んだテナントを残りの処理の間だけ束縛し、下流の例外をそのまま戻すことを検証する（ADR-105）。 */
class TenantSelectionFilterTest {

  /** 選ぶテナントの ID。 */
  private static final String TENANT = "5d2e8f4a-1b3c-4d6e-9f70-8a1b2c3d4e5f";

  /** 選ぶテナントの役割。 */
  private static final String ROLE = "MEMBER";

  /** テナントの所属を、利用者の ID に関わらず返す参照。 */
  private final TenancyQueries tenancyQueries =
      new TenancyQueries() {
        @Override
        public List<MembershipSummary> memberships(final String userId) {
          return List.of(new MembershipSummary(TENANT, "tenant", ROLE));
        }

        @Override
        public Optional<MembershipSummary> membership(final String userId, final String tenantId) {
          return Optional.of(new MembershipSummary(TENANT, "tenant", ROLE));
        }

        @Override
        public Optional<TenantMembersDetails> findMembers(final String tenantId) {
          return Optional.empty();
        }
      };

  /** 検査する filter。 */
  private final TenantSelectionFilter filter =
      new TenantSelectionFilter(tenancyQueries, new TenantSession());

  @BeforeEach
  void authenticateWithSelectedTenant() {
    final Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
                (AppUserPrincipal) () -> "user-1", "n/a", List.of())
            .toBuilder()
            .authorities(
                authorities -> authorities.add(new TenantMembershipAuthority(TENANT, ROLE)))
            .build();
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("選んだテナントは、残りの処理の間だけ束縛され、終わると外れる")
  void bindsSelectedTenantOnlyWhileFilterChainRuns() throws IOException, ServletException {
    final List<TenantId> seen = new ArrayList<>();

    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/orders"),
        new MockHttpServletResponse(),
        (request, response) -> seen.add(TenantContext.current()));

    assertThat(seen).extracting(TenantId::toString).containsExactly(TENANT);
    assertThatThrownBy(TenantContext::current).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("下流が投げた IOException は、そのまま戻る")
  void ioExceptionFromChainIsRethrownAsIs() {
    final IOException failure = new IOException("broken pipe");

    assertThatThrownBy(() ->
            doFilter((request, response) -> {
              throw failure;
            }))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("下流が投げた UncheckedIOException は、IOException に変えず、そのまま戻る")
  void uncheckedIoExceptionFromChainIsRethrownAsIs() {
    final UncheckedIOException failure = new UncheckedIOException(new IOException("broken pipe"));

    assertThatThrownBy(() ->
            doFilter((request, response) -> {
              throw failure;
            }))
        .isSameAs(failure);
  }

  private void doFilter(final FilterChain chain) throws IOException, ServletException {
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/orders"), new MockHttpServletResponse(), chain);
  }
}
