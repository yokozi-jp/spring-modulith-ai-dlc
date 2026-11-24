package com.example.demo.tenancy.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import com.example.demo.tenancy.TenantMembersDetails;
import io.micrometer.common.KeyValue;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.ServerHttpObservationFilter;

/** {@link TenantSelectionFilter} が、選んだテナントを残りの処理の間だけ束縛し、下流の例外をそのまま戻すことを検証する（ADR-105）。 */
@SuppressWarnings("PMD.TooManyMethods")
class TenantSelectionFilterTest {

  /** 選ぶテナントの ID。 */
  private static final String TENANT = "5d2e8f4a-1b3c-4d6e-9f70-8a1b2c3d4e5f";

  /** リクエストの HTTP メソッド。 */
  private static final String GET = "GET";

  /** テナントを選んでいる必要がある API のパス。 */
  private static final String ORDERS = "/api/orders";

  /** 認証した利用者の、アプリの利用者の ID。 */
  private static final String USER = "user-1";

  /** observation で利用者の ID を持つ項目の名前。 */
  private static final String USER_ID_KEY = "user.id";

  /** MDC と observation でテナントの ID を持つ項目の名前。 */
  private static final String TENANT_ID_KEY = "tenant.id";

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
                (AppUserPrincipal) () -> USER, "n/a", List.of())
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
        new MockHttpServletRequest(GET, ORDERS),
        new MockHttpServletResponse(),
        (request, response) -> seen.add(TenantContext.current()));

    assertThat(seen).extracting(TenantId::toString).containsExactly(TENANT);
    assertThatThrownBy(TenantContext::current).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("選んだテナントの残りの処理の間だけ、MDC にテナントの ID が入り、終わると消える")
  void putsTenantIntoMdcOnlyWhileFilterChainRuns() throws IOException, ServletException {
    final List<@Nullable String> seen = new ArrayList<>();

    doFilter((request, response) -> seen.add(MDC.get(TENANT_ID_KEY)));

    assertThat(seen).as("残りの処理の中の tenant.id").containsExactly(TENANT);
    assertThat(MDC.get(TENANT_ID_KEY)).as("filter を出た後の tenant.id").isNull();
  }

  @Test
  @DisplayName("利用者の ID と選んだテナントを、受信の observation の高い濃度の key value にだけ足す")
  void addsUserAndTenantToServerObservationAsHighCardinality()
      throws IOException, ServletException {
    final MockHttpServletRequest request = new MockHttpServletRequest(GET, ORDERS);
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final ServerRequestObservationContext context = observe(request, response);

    filter.doFilter(request, response, (chainRequest, chainResponse) -> {});

    assertThat(context.getHighCardinalityKeyValues())
        .containsExactlyInAnyOrder(
            KeyValue.of(USER_ID_KEY, USER), KeyValue.of(TENANT_ID_KEY, TENANT));
    assertThat(context.getLowCardinalityKeyValues()).as("メトリクスの tag になる key value").isEmpty();
  }

  @Test
  @DisplayName("認証していないリクエストは、MDC にテナントの ID を、受信の observation に利用者の ID とテナントの ID を入れない")
  void unauthenticatedRequestAddsNeitherUserNorTenant() throws IOException, ServletException {
    SecurityContextHolder.clearContext();
    final MockHttpServletRequest request = new MockHttpServletRequest(GET, ORDERS);
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final ServerRequestObservationContext context = observe(request, response);
    final List<@Nullable String> seen = new ArrayList<>();

    filter.doFilter(
        request, response, (chainRequest, chainResponse) -> seen.add(MDC.get(TENANT_ID_KEY)));

    assertThat(seen).as("残りの処理の中の tenant.id").containsExactly((String) null);
    assertThat(context.getHighCardinalityKeyValues()).as("受信の observation の key value").isEmpty();
  }

  @Test
  @DisplayName("利用者が AppUserPrincipal でない認証は、受信の observation に利用者の ID を入れない")
  void nonAppUserPrincipalAddsNoUserIdToServerObservation() throws IOException, ServletException {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(USER, "n/a", List.of()));
    final MockHttpServletRequest request =
        new MockHttpServletRequest(GET, MembershipController.PATH);
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final ServerRequestObservationContext context = observe(request, response);

    filter.doFilter(request, response, (chainRequest, chainResponse) -> {});

    assertThat(context.getHighCardinalityKeyValues()).as("受信の observation の key value").isEmpty();
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

  /** リクエストに、受信の observation を持たせて返す。 */
  private static ServerRequestObservationContext observe(
      final MockHttpServletRequest request, final MockHttpServletResponse response) {
    final ServerRequestObservationContext context =
        new ServerRequestObservationContext(request, response);
    request.setAttribute(
        ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
    return context;
  }

  private void doFilter(final FilterChain chain) throws IOException, ServletException {
    filter.doFilter(new MockHttpServletRequest(GET, ORDERS), new MockHttpServletResponse(), chain);
  }
}
