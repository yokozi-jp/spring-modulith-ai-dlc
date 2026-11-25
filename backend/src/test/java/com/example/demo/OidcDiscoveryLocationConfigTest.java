package com.example.demo;

import static com.example.demo.testkit.LoopbackHttpServer.reply;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.testkit.LoopbackHttpServer;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** {@link OidcDiscoveryLocationConfig} の条件、issuer の検査、registration の内容を、ローカルの HTTP スタブで検証する。 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class OidcDiscoveryLocationConfigTest {

  /** ブラウザ向けの公開用の issuer。名前解決できないため、接続を試みると起動に失敗する。 */
  private static final String PUBLIC_ISSUER = "http://issuer.example.test/realms/demo";

  /** discovery を返すスタブのパス。 */
  private static final String DISCOVERY_PATH = "/discovery";

  /** discovery を返すローカルの HTTP スタブ。 */
  private static LoopbackHttpServer server;

  /** スタブの base URL。 */
  private static String baseUrl;

  /** Boot の OAuth2 client の自動構成と、検証対象の設定を読み込む runner。 */
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  OAuth2ClientAutoConfiguration.class,
                  RestClientAutoConfiguration.class,
                  HttpClientAutoConfiguration.class,
                  ImperativeHttpClientAutoConfiguration.class))
          .withUserConfiguration(OidcDiscoveryLocationConfig.class)
          .withPropertyValues(
              "spring.http.clients.imperative.factory=jdk",
              "spring.http.clients.redirects=dont-follow",
              "spring.security.oauth2.client.registration.web.provider=oidc",
              "spring.security.oauth2.client.registration.web.client-id=test-client",
              "spring.security.oauth2.client.registration.web.client-secret=test-secret",
              "spring.security.oauth2.client.registration.web.client-authentication-method=client_secret_basic",
              "spring.security.oauth2.client.registration.web.authorization-grant-type=authorization_code",
              "spring.security.oauth2.client.registration.web.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
              "spring.security.oauth2.client.registration.web.scope=openid,profile");

  @BeforeAll
  static void startServer() throws IOException {
    server = LoopbackHttpServer.start();
    baseUrl = server.url();
    // 内部の URL の discovery。issuer は公開用の URL を返す（Keycloak の hostname v2 と同じ）。
    server.handle(DISCOVERY_PATH, exchange -> reply(exchange, 200, discovery(PUBLIC_ISSUER)));
    // issuer がスタブ自身の URL である discovery。Boot の自動構成が issuer-uri から読む。
    server.handle(
        "/realms/demo/.well-known/openid-configuration",
        exchange -> reply(exchange, 200, discovery(baseUrl + "/realms/demo")));
    // discovery の URL への 302。辿ると discovery が読めてしまう。
    server.handle(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", baseUrl + DISCOVERY_PATH);
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
  }

  @AfterAll
  static void stopServer() {
    server.stop();
  }

  @Test
  @DisplayName("discovery の issuer が issuer-uri と食い違うと起動しない")
  void failsWhenDiscoveryIssuerDiffersFromIssuerUri() {
    runner
        .withPropertyValues(
            "oidc.discovery-uri=" + baseUrl + DISCOVERY_PATH,
            "spring.security.oauth2.client.provider.oidc.issuer-uri=http://other.example.test/realms/demo")
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .as("discovery-uri=%s/discovery の起動失敗", baseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PUBLIC_ISSUER)
                .hasMessageContaining("http://other.example.test/realms/demo"));
  }

  @Test
  @DisplayName("discovery がリダイレクトを返しても辿らず、起動しない")
  void doesNotFollowRedirects() {
    runner
        .withPropertyValues(
            "oidc.discovery-uri=" + baseUrl + "/redirect",
            "spring.security.oauth2.client.provider.oidc.issuer-uri=" + PUBLIC_ISSUER)
        .run(context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .as("discovery-uri=%s/redirect の起動失敗", baseUrl)
                .hasMessageContaining("OIDC discovery の応答が空である"));
  }

  @Test
  @DisplayName("registration に discovery の issuer と end_session_endpoint が入る")
  void registrationKeepsIssuerAndEndSessionEndpoint() {
    runner
        .withPropertyValues(
            "oidc.discovery-uri=" + baseUrl + DISCOVERY_PATH,
            "spring.security.oauth2.client.provider.oidc.issuer-uri=" + PUBLIC_ISSUER)
        .run(context -> {
          final ClientRegistration registration =
              context.getBean(ClientRegistrationRepository.class).findByRegistrationId("web");
          assertThat(registration.getProviderDetails().getIssuerUri())
              .as("registration=web の issuerUri")
              .isEqualTo(PUBLIC_ISSUER);
          assertThat(registration.getProviderDetails().getConfigurationMetadata())
              .as("registration=web の discovery の metadata")
              .containsEntry("end_session_endpoint", PUBLIC_ISSUER + "/logout");
          assertThat(registration.getProviderDetails().getTokenUri())
              .as("registration=web の token endpoint")
              .isEqualTo("http://keycloak:8080/realms/demo/token");
          assertThat(registration.getClientId()).as("registration=web").isEqualTo("test-client");
          assertThat(registration.getScopes())
              .as("registration=web")
              .containsExactlyInAnyOrder("openid", "profile");
        });
  }

  @Test
  @DisplayName("discovery-uri が無ければ bean を作らず、Boot の自動構成のままである")
  void keepsBootAutoConfigurationWithoutDiscoveryUri() {
    runner
        .withPropertyValues(
            "spring.security.oauth2.client.provider.oidc.issuer-uri=" + baseUrl + "/realms/demo")
        .run(context -> {
          assertThat(context)
              .doesNotHaveBean(OidcDiscoveryLocationConfig.class)
              .hasSingleBean(ClientRegistrationRepository.class);
          assertThat(
                  context
                      .getBean(ClientRegistrationRepository.class)
                      .findByRegistrationId("web")
                      .getProviderDetails()
                      .getIssuerUri())
              .as("Boot の自動構成が issuer-uri から読んだ registration=web の issuerUri")
              .isEqualTo(baseUrl + "/realms/demo");
        });
  }

  private static String discovery(final String issuer) {
    return """
    {
      "issuer": "ISSUER",
      "authorization_endpoint": "ISSUER/auth",
      "token_endpoint": "http://keycloak:8080/realms/demo/token",
      "jwks_uri": "http://keycloak:8080/realms/demo/certs",
      "userinfo_endpoint": "http://keycloak:8080/realms/demo/userinfo",
      "end_session_endpoint": "ISSUER/logout",
      "response_types_supported": ["code"],
      "subject_types_supported": ["public"],
      "id_token_signing_alg_values_supported": ["RS256"],
      "grant_types_supported": ["authorization_code"],
      "token_endpoint_auth_methods_supported": ["client_secret_basic"]
    }
    """
        .replace("ISSUER", issuer);
  }
}
