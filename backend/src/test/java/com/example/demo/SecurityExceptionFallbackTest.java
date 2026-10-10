package com.example.demo;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.example.demo.testkit.SharedTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * API の entry point と access denied handler が、resolver の扱わなかった例外を sendError へ落とすことを確かめる。
 *
 * <p>本番では {@code ApiExceptionHandler} が認証と認可の例外を常に扱うため、resolver を spy にして null を返させる。 sendError の後の
 * {@code /error} への転送はサーブレットコンテナが行い MockMvc では再現できないので、実サーバーを起動する。 spy と実サーバーで context が 1
 * つ増えるが、この転送を確かめるために必要である。
 */
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SharedTestConfiguration.class)
class SecurityExceptionFallbackTest {

  /** entry point と access denied handler に注入される MVC の resolver。 */
  @MockitoSpyBean(name = "handlerExceptionResolver")
  private HandlerExceptionResolver handlerExceptionResolver;

  /** 起動したサーバーのポート。 */
  @LocalServerPort private int port;

  /** 実サーバーへ HTTP で要求するクライアント。 */
  private RestTestClient client;

  @BeforeEach
  void setUp() {
    doReturn(null)
        .when(handlerExceptionResolver)
        .resolveException(any(), any(), any(), any(AuthenticationException.class));
    doReturn(null)
        .when(handlerExceptionResolver)
        .resolveException(any(), any(), any(), any(AccessDeniedException.class));
    client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  @DisplayName("resolver が扱わなかった未認証は /error 経由で 401 の Problem Details を返す")
  void unresolvedAuthenticationFallsBackToProblemDetails() {
    client
        .get()
        .uri("/api/missing")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(401);
  }

  @Test
  @DisplayName("resolver が扱わなかった CSRF の拒否は /error 経由で 403 の Problem Details を返す")
  void unresolvedAccessDeniedFallsBackToProblemDetails() {
    client
        .post()
        .uri("/api/missing")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(403);
  }
}
