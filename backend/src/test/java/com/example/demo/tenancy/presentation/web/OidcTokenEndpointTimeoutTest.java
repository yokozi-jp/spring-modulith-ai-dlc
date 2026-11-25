package com.example.demo.tenancy.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.testkit.SharedTestConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;

/** IdP のトークンのエンドポイントが応答しないとき、ログインのコールバックが設定したタイムアウトで失敗することを、実際の Spring の構成で検証する。 */
@SpringBootTest(properties = "spring.http.clients.read-timeout=500ms")
@Import(SharedTestConfiguration.class)
class OidcTokenEndpointTimeoutTest {

  /** 応答を返さないまま待たせる上限。タイムアウトが効かないと、テストはこの時間で失敗する。 */
  private static final Duration HANG = Duration.ofSeconds(10);

  /** oauth2Login が使うはずの、認可コードをトークンに替えるクライアント。 */
  @Autowired
  private OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenClient;

  /** 共有構成の OIDC クライアント登録。 */
  @Autowired private ClientRegistrationRepository registrations;

  /** 実際の Security filter chain。 */
  @Autowired private FilterChainProxy springSecurityFilterChain;

  /** 応答しない IdP のトークンのエンドポイント。 */
  private HttpServer idp;

  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void startUnresponsiveIdp() throws IOException {
    idp = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    idp.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    idp.createContext(
        "/token",
        exchange -> {
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    idp.createContext(
        "/error",
        exchange -> {
          final byte[] body = "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(400, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    idp.start();
  }

  @AfterEach
  void stopIdp() {
    release.countDown();
    idp.stop(0);
  }

  @Test
  @DisplayName("oauth2Login は、Boot の設定を通したトークンのクライアントを使う")
  void oauth2LoginUsesTheTokenClientBean() {
    final var tokenClients =
        springSecurityFilterChain.getFilterChains().stream()
            .flatMap(chain -> chain.getFilters().stream())
            .filter(OAuth2LoginAuthenticationFilter.class::isInstance)
            .map(filter -> ReflectionTestUtils.getField(filter, "authenticationManager"))
            .map(OidcProviders::oidcProvider)
            .map(provider -> ReflectionTestUtils.getField(provider, "accessTokenResponseClient"))
            .toList();

    assertThat(tokenClients).containsExactly(tokenClient);
  }

  @Test
  @DisplayName("トークンのエンドポイントが OAuth 2.0 のエラーを返すと、OAuth2AuthorizationException になる")
  void oauth2ErrorResponseIsMappedToAuthorizationException() {
    assertThatThrownBy(() -> tokenClient.getTokenResponse(grantRequest("/error")))
        .isInstanceOf(OAuth2AuthorizationException.class)
        .hasMessageContaining("invalid_grant");
  }

  @Test
  @DisplayName("トークンのエンドポイントが応答しないと、設定した read-timeout で失敗する")
  void unresponsiveTokenEndpointFailsAtTheReadTimeout() {
    final var request = grantRequest("/token");

    final long start = System.nanoTime();
    assertThatThrownBy(() -> tokenClient.getTokenResponse(request))
        .isInstanceOf(RuntimeException.class);
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(elapsed)
        .as("失敗までの時間")
        .isGreaterThanOrEqualTo(Duration.ofMillis(400))
        .isLessThan(HANG);
  }

  private OAuth2AuthorizationCodeGrantRequest grantRequest(final String path) {
    final ClientRegistration registration =
        ClientRegistration.withClientRegistration(registrations.findByRegistrationId("web"))
            .tokenUri("http://127.0.0.1:" + idp.getAddress().getPort() + path)
            .build();
    final OAuth2AuthorizationRequest authorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
            .clientId(registration.getClientId())
            .redirectUri("http://localhost/login/oauth2/code/web")
            .state("state")
            .build();
    final OAuth2AuthorizationResponse authorizationResponse =
        OAuth2AuthorizationResponse.success("code")
            .redirectUri("http://localhost/login/oauth2/code/web")
            .state("state")
            .build();
    return new OAuth2AuthorizationCodeGrantRequest(
        registration, new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
  }
}
