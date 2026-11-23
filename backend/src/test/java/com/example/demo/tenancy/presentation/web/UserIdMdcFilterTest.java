package com.example.demo.tenancy.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.security.AppUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@link UserIdMdcFilter} が、アプリの利用者として認証したリクエストの残りの処理の間だけ、MDC に利用者の ID を入れることを検証する。 */
class UserIdMdcFilterTest {

  /** 認証した利用者の、アプリの利用者の ID。 */
  private static final String USER = "user-1";

  /** MDC で利用者の ID を持つ項目の名前。 */
  private static final String USER_ID_KEY = "user.id";

  /** 残りの処理の中で見た MDC の値の説明。 */
  private static final String SEEN_DESCRIPTION = "残りの処理の中の user.id";

  /** 検査する filter。 */
  private final UserIdMdcFilter filter = new UserIdMdcFilter();

  /** 残りの処理の中で見た MDC の利用者の ID。 */
  private final List<@Nullable String> seen = new ArrayList<>();

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("アプリの利用者として認証したリクエストの残りの処理の間だけ、MDC に利用者の ID が入り、終わると消える")
  void putsUserIdOnlyWhileFilterChainRuns() throws IOException, ServletException {
    authenticate(appUser());

    doFilter((request, response) -> seen.add(MDC.get(USER_ID_KEY)));

    assertThat(seen).as(SEEN_DESCRIPTION).containsExactly(USER);
    assertThat(MDC.get(USER_ID_KEY)).as("filter を出た後の user.id").isNull();
  }

  @Test
  @DisplayName("残りの処理が例外を投げても、MDC の利用者の ID は消え、例外はそのまま戻る")
  void removesUserIdWhenFilterChainThrows() {
    authenticate(appUser());
    final IllegalStateException failure = new IllegalStateException("downstream failure");

    assertThatThrownBy(() ->
            doFilter((request, response) -> {
              seen.add(MDC.get(USER_ID_KEY));
              throw failure;
            }))
        .isSameAs(failure);
    assertThat(seen).as(SEEN_DESCRIPTION).containsExactly(USER);
    assertThat(MDC.get(USER_ID_KEY)).as("filter を出た後の user.id").isNull();
  }

  @Test
  @DisplayName("認証していないリクエストは、MDC に利用者の ID を入れない")
  void unauthenticatedRequestAddsNoUserId() throws IOException, ServletException {
    doFilter((request, response) -> seen.add(MDC.get(USER_ID_KEY)));

    assertThat(seen).as(SEEN_DESCRIPTION).containsExactly((String) null);
  }

  @Test
  @DisplayName("匿名の認証は、利用者が AppUserPrincipal でも、MDC に利用者の ID を入れない")
  void anonymousAuthenticationAddsNoUserId() throws IOException, ServletException {
    authenticate(
        new AnonymousAuthenticationToken(
            "key",
            (AppUserPrincipal) () -> USER,
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

    doFilter((request, response) -> seen.add(MDC.get(USER_ID_KEY)));

    assertThat(seen).as(SEEN_DESCRIPTION).containsExactly((String) null);
  }

  @Test
  @DisplayName("認証を終えていない認証は、利用者が AppUserPrincipal でも、MDC に利用者の ID を入れない")
  void unauthenticatedTokenAddsNoUserId() throws IOException, ServletException {
    authenticate(
        UsernamePasswordAuthenticationToken.unauthenticated((AppUserPrincipal) () -> USER, "n/a"));

    doFilter((request, response) -> seen.add(MDC.get(USER_ID_KEY)));

    assertThat(seen).as(SEEN_DESCRIPTION).containsExactly((String) null);
  }

  @Test
  @DisplayName("利用者が AppUserPrincipal でない認証は、MDC に利用者の ID を入れない")
  void nonAppUserPrincipalAddsNoUserId() throws IOException, ServletException {
    authenticate(UsernamePasswordAuthenticationToken.authenticated(USER, "n/a", List.of()));

    doFilter((request, response) -> seen.add(MDC.get(USER_ID_KEY)));

    assertThat(seen).as(SEEN_DESCRIPTION).containsExactly((String) null);
  }

  private static Authentication appUser() {
    return UsernamePasswordAuthenticationToken.authenticated(
        (AppUserPrincipal) () -> USER, "n/a", List.of());
  }

  private static void authenticate(final Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private void doFilter(final FilterChain chain) throws IOException, ServletException {
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/orders"), new MockHttpServletResponse(), chain);
  }
}
