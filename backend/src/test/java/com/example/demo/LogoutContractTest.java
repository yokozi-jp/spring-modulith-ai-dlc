package com.example.demo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * ログアウトの CSRF 保護、IdP の end session endpoint への redirect、セッションの破棄を検証する。
 *
 * <p>ログアウト後の古い Cookie を確かめるため、{@code oidcLogin()} ではなく Redis に保存したセッションと、そのセッション Cookie を通す。 Spring
 * Security 7 は request post processor の認証をセッションへ保存しないためである。
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyStaticImports"})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class LogoutContractTest {

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** ログイン済みのセッションを用意し、ログアウト後に残っているかを確かめる Redis のリポジトリ。 */
  @Autowired private RedisSessionRepository sessionRepository;

  /** テストごとに作るセッションの ID。 */
  private String sessionId;

  /** {@link #sessionId} を指す、ブラウザが送るのと同じ形の Cookie。 */
  private Cookie sessionCookie;

  @BeforeEach
  @SuppressWarnings("PMD.SignatureDeclareThrowsException") // MockMvc#perform が Exception を投げるため。
  void createSession() throws Exception {
    final List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
    final OidcIdToken idToken =
        new OidcIdToken(
            "id-token",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T01:00:00Z"),
            Map.of("sub", "test-user"));
    sessionId =
        saveSession(
            sessionRepository,
            new SecurityContextImpl(
                new OAuth2AuthenticationToken(
                    new DefaultOidcUser(authorities, idToken), authorities, "web")));
    // DefaultCookieSerializer の既定どおり、Cookie の値は session ID の Base64 にする。
    // 埋め込みサーバのない MockMvc では server.servlet.session.cookie.name（APP_SESSION）が適用されず、
    // Spring Session の既定名 SESSION になる。APP_SESSION の名前は E2E が実際のサーバで確かめる。
    sessionCookie =
        new Cookie("SESSION", Base64.getEncoder().encodeToString(sessionId.getBytes(UTF_8)));

    // 準備したセッションで認証済みになることを先に示す。Base64 の前提が崩れると、ここで 401 になる。
    mockMvc.perform(get("/api/missing").cookie(sessionCookie)).andExpect(status().isNotFound());
  }

  // RedisSessionRepository のセッションの型は公開されていないため、型変数で受けて Session として扱う。
  private static <S extends Session> String saveSession(
      final SessionRepository<S> repository, final SecurityContext securityContext) {
    final S session = repository.createSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
    repository.save(session);
    return session.getId();
  }

  @AfterEach
  void deleteSession() {
    sessionRepository.deleteById(sessionId);
  }

  @Test
  @DisplayName("CSRF トークンのない POST /logout は 403 になり、セッションを残す")
  void logoutWithoutCsrfTokenIsForbidden() throws Exception {
    mockMvc.perform(post("/logout").cookie(sessionCookie)).andExpect(status().isForbidden());

    assertNotNull(
        sessionRepository.findById(sessionId),
        () -> "POST /logout（CSRF トークンなし）の後にセッションが消えた: sessionId=" + sessionId);
    mockMvc.perform(get("/api/missing").cookie(sessionCookie)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("正しい CSRF トークン付きの POST /logout は IdP の end session endpoint へ redirect する")
  void logoutRedirectsToIdpEndSessionEndpoint() throws Exception {
    final String location =
        mockMvc
            .perform(post("/logout").cookie(sessionCookie).with(csrf()))
            .andExpect(status().isFound())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.LOCATION);

    assertNotNull(location, () -> "POST /logout の Location がない: sessionId=" + sessionId);
    assertTrue(
        location.startsWith("https://issuer.example.test/oauth2/logout?"),
        () -> "POST /logout の redirect 先: sessionId=" + sessionId + ", location=" + location);
    final String postLogoutRedirectUri =
        UriComponentsBuilder.fromUriString(location)
            .build()
            .getQueryParams()
            .getFirst("post_logout_redirect_uri");
    assertNotNull(
        postLogoutRedirectUri,
        () -> "post_logout_redirect_uri がない: sessionId=" + sessionId + ", location=" + location);
    assertEquals(
        "http://localhost/logged-out",
        UriUtils.decode(postLogoutRedirectUri, UTF_8),
        () -> "POST /logout の post_logout_redirect_uri: location=" + location);
  }

  @Test
  @DisplayName("GET /logout ではログアウトせず、同じ Cookie で認証済みのまま使える")
  void logoutByGetDoesNotEndSession() throws Exception {
    // GET /logout の status は Spring Security の既定ページの実装詳細なので確かめない。
    mockMvc.perform(get("/logout").cookie(sessionCookie));

    assertNotNull(
        sessionRepository.findById(sessionId),
        () -> "GET /logout の後にセッションが消えた: sessionId=" + sessionId);
    mockMvc.perform(get("/api/missing").cookie(sessionCookie)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("ログアウト後は、それまでのセッション Cookie で /api/** を呼ぶと 401 になる")
  void oldSessionCookieIsUnauthorizedAfterLogout() throws Exception {
    mockMvc
        .perform(post("/logout").cookie(sessionCookie).with(csrf()))
        .andExpect(status().isFound());

    assertNull(
        sessionRepository.findById(sessionId),
        () -> "POST /logout の後もセッションが Redis に残っている: sessionId=" + sessionId);
    mockMvc.perform(get("/api/missing").cookie(sessionCookie)).andExpect(status().isUnauthorized());
  }
}
