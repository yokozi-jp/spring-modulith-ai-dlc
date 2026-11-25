package com.example.demo.tenancy.presentation.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.demo.OidcTokenClientConfig;
import com.example.demo.testkit.LoopbackHttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.web.client.ResourceAccessException;

/** IdP のトークンのエンドポイントが応答しないとき、トークンの取得が {@code spring.http.clients.read-timeout} で失敗することを確かめる。 */
class OidcTokenClientTimeoutTest {

  /** 応答を返さない handler を待たせる上限。タイムアウトが効かなくても、この時間で handler が戻り、テストが失敗する。 */
  private static final long HANDLER_WAIT_SECONDS = 5;

  /** トークンのエンドポイントのパス。 */
  private static final String TOKEN_PATH = "/token";

  /** 認可の要求と応答で使うリダイレクト URI。 */
  private static final String REDIRECT_URI = "http://localhost/login/oauth2/code/web";

  /**
   * {@code OidcTokenClientConfig} と、{@code spring.http.clients.*} を RestClient.Builder
   * へ渡す自動構成だけを持つ文脈。
   */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(context ->
              context
                  .getBeanFactory()
                  .setConversionService(ApplicationConversionService.getSharedInstance()))
          .withConfiguration(
              AutoConfigurations.of(
                  RestClientAutoConfiguration.class,
                  HttpClientAutoConfiguration.class,
                  ImperativeHttpClientAutoConfiguration.class))
          .withUserConfiguration(OidcTokenClientConfig.class)
          .withPropertyValues(
              "spring.http.clients.imperative.factory=jdk",
              "spring.http.clients.connect-timeout=1s",
              "spring.http.clients.read-timeout=500ms");

  /** 応答しない IdP の代わりの HTTP サーバ。 */
  private @Nullable LoopbackHttpServer idp;

  @AfterEach
  void stopIdp() {
    if (idp != null) {
      idp.stop();
    }
  }

  @Test
  @DisplayName("トークンのエンドポイントが応答しないと、read-timeout の後に失敗する")
  void unresponsiveTokenEndpointFailsAtTheReadTimeout() throws IOException {
    final CountDownLatch release = new CountDownLatch(1);
    idp =
        LoopbackHttpServer.start()
            .handle(
                TOKEN_PATH,
                exchange -> {
                  try (exchange) {
                    release.await(HANDLER_WAIT_SECONDS, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    throw (InterruptedIOException) new InterruptedIOException().initCause(e);
                  }
                });
    final OAuth2AuthorizationCodeGrantRequest request = grantRequest(idp.url() + TOKEN_PATH);

    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicReference<Duration> elapsed = new AtomicReference<>();
    contextRunner.run(context -> {
      final RestClientAuthorizationCodeTokenResponseClient client =
          context.getBean(RestClientAuthorizationCodeTokenResponseClient.class);
      final long started = System.nanoTime();
      failure.set(catchThrowable(() -> client.getTokenResponse(request)));
      elapsed.set(Duration.ofNanos(System.nanoTime() - started));
      release.countDown();
    });

    assertThat(failure.get())
        .isInstanceOf(OAuth2AuthorizationException.class)
        .hasCauseInstanceOf(ResourceAccessException.class);
    assertThat(elapsed.get())
        .as("応答しないトークンのエンドポイントに失敗するまでの時間（read-timeout=500ms）")
        .isBetween(Duration.ofMillis(400), Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("トークンのエンドポイントが返す JSON を、OAuth2AccessTokenResponse として読む")
  void readsTheTokenResponseAsJson() throws IOException {
    idp =
        LoopbackHttpServer.start()
            .handle(
                TOKEN_PATH,
                exchange ->
                    LoopbackHttpServer.reply(
                        exchange,
                        200,
                        "{\"access_token\":\"abc\",\"token_type\":\"Bearer\",\"expires_in\":300}"));
    final OAuth2AuthorizationCodeGrantRequest request = grantRequest(idp.url() + TOKEN_PATH);

    final AtomicReference<OAuth2AccessTokenResponse> response = new AtomicReference<>();
    // read-timeout は本文の読み取りまで含む。最初の要求は JVM の初期化で 500ms を超えうるため、長くする。
    contextRunner
        .withPropertyValues("spring.http.clients.read-timeout=30s")
        .run(context ->
            response.set(
                context
                    .getBean(RestClientAuthorizationCodeTokenResponseClient.class)
                    .getTokenResponse(request)));

    assertThat(response.get().getAccessToken().getTokenValue()).isEqualTo("abc");
  }

  private static OAuth2AuthorizationCodeGrantRequest grantRequest(final String tokenUri) {
    final ClientRegistration registration =
        ClientRegistration.withRegistrationId("web")
            .clientId("test-web-client")
            .clientSecret("test-web-client-secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(REDIRECT_URI)
            .authorizationUri("http://localhost/authorize")
            .tokenUri(tokenUri)
            .build();
    final OAuth2AuthorizationRequest authorizationRequest =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
            .clientId(registration.getClientId())
            .redirectUri(REDIRECT_URI)
            .state("state")
            .build();
    final OAuth2AuthorizationResponse authorizationResponse =
        OAuth2AuthorizationResponse.success("code")
            .redirectUri(REDIRECT_URI)
            .state("state")
            .build();
    return new OAuth2AuthorizationCodeGrantRequest(
        registration, new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
  }
}
