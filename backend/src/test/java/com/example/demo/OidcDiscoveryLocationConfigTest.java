package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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

  /** discovery を返すローカルの HTTP スタブ。 */
  private static HttpServer server;

  /** スタブの base URL。 */
  private static String baseUrl;

  /** Boot の OAuth2 client の自動構成と、検証対象の設定を読み込む runner。 */
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
          .withUserConfiguration(OidcDiscoveryLocationConfig.class)
          .withPropertyValues(
              "spring.security.oauth2.client.registration.web.provider=oidc",
              "spring.security.oauth2.client.registration.web.client-id=test-client",
              "spring.security.oauth2.client.registration.web.client-secret=test-secret",
              "spring.security.oauth2.client.registration.web.client-authentication-method=client_secret_basic",
              "spring.security.oauth2.client.registration.web.authorization-grant-type=authorization_code",
              "spring.security.oauth2.client.registration.web.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
              "spring.security.oauth2.client.registration.web.scope=openid,profile");

  @BeforeAll
  static void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    // 内部の URL の discovery。issuer は公開用の URL を返す（Keycloak の hostname v2 と同じ）。
    server.createContext("/discovery", exchange -> respond(exchange, discovery(PUBLIC_ISSUER)));
    // issuer がスタブ自身の URL である discovery。Boot の自動構成が issuer-uri から読む。
    server.createContext(
        "/realms/demo/.well-known/openid-configuration",
        exchange -> respond(exchange, discovery(baseUrl + "/realms/demo")));
    server.start();
  }

  @AfterAll
  static void stopServer() {
    server.stop(0);
  }

  @Test
  @DisplayName("discovery の issuer が issuer-uri と食い違うと起動しない")
  void failsWhenDiscoveryIssuerDiffersFromIssuerUri() {
    runner
        .withPropertyValues(
            "oidc.discovery-uri=" + baseUrl + "/discovery",
            "spring.security.oauth2.client.provider.oidc.issuer-uri=http://other.example.test/realms/demo")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .as("discovery-uri=%s/discovery の起動失敗", baseUrl)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PUBLIC_ISSUER)
                    .hasMessageContaining("http://other.example.test/realms/demo"));
  }

  @Test
  @DisplayName("registration に discovery の issuer と end_session_endpoint が入る")
  void registrationKeepsIssuerAndEndSessionEndpoint() {
    runner
        .withPropertyValues(
            "oidc.discovery-uri=" + baseUrl + "/discovery",
            "spring.security.oauth2.client.provider.oidc.issuer-uri=" + PUBLIC_ISSUER)
        .run(
            context -> {
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
              assertThat(registration.getClientId())
                  .as("registration=web")
                  .isEqualTo("test-client");
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
        .run(
            context -> {
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

  private static void respond(final HttpExchange exchange, final String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
