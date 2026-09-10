package com.example.demo;

import static com.example.demo.jooq.tables.EventPublication.EVENT_PUBLICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/** アプリケーション起動時の日時・DB・OIDC(PKCE) 設定が規約どおりであることを検証する統合テスト。 */
@SpringBootTest
@Import(DemoApplicationTests.OidcTestConfiguration.class)
class DemoApplicationTests {

  /** UTC 固定を検証する対象のアプリケーション {@code Clock}。 */
  @Autowired private Clock clock;

  /** DB セッションのタイムゾーンと {@code timestamptz} 往復を検証するための {@code JdbcTemplate}。 */
  @Autowired private JdbcTemplate jdbcTemplate;

  /** 生成コードの日時型とPostgreSQL間の往復を検証するためのjOOQコンテキスト。 */
  @Autowired private DSLContext dslContext;

  /** PKCE パラメータを検証する対象の認可リクエストリゾルバ。 */
  @Autowired private OAuth2AuthorizationRequestResolver authorizationRequestResolver;

  /** 起動確認の対象となる {@code ApplicationContext}。 */
  @Autowired private ApplicationContext applicationContext;

  @Test
  void contextLoads() {
    assertNotNull(applicationContext, "ApplicationContext が起動できること");
  }

  @Test
  void applicationClockUsesUtc() {
    assertEquals(ZoneOffset.UTC, clock.getZone(), "アプリケーション Clock は UTC 固定であること");
  }

  @Test
  void databaseSessionUsesUtc() {
    assertEquals(
        "UTC",
        jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class),
        "DB セッションのタイムゾーンは UTC であること");
  }

  @Test
  void instantRoundTripsThroughTimestampWithTimeZone() {
    final Instant expected = Instant.parse("2026-09-07T06:18:42.567123Z");
    final OffsetDateTime actual =
        jdbcTemplate.queryForObject(
            "SELECT CAST(? AS TIMESTAMP WITH TIME ZONE)",
            OffsetDateTime.class,
            expected.atOffset(ZoneOffset.UTC));

    assertNotNull(actual, "timestamptz へキャストした結果が取得できること");
    assertEquals(expected, actual.toInstant(), "timestamptz 往復で Instant が保存されること");
  }

  @Test
  void generatedJooqInstantRoundTripsThroughTimestampWithTimeZone() {
    final UUID publicationId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    final Instant expected = Instant.parse("2026-09-09T07:44:32.364123Z");

    try {
      assertEquals(
          1,
          dslContext
              .insertInto(EVENT_PUBLICATION)
              .set(EVENT_PUBLICATION.ID, publicationId)
              .set(EVENT_PUBLICATION.LISTENER_ID, "jooq-instant-test")
              .set(EVENT_PUBLICATION.EVENT_TYPE, "test.event")
              .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{}")
              .set(EVENT_PUBLICATION.PUBLICATION_DATE, expected)
              .execute(),
          "生成jOOQフィールドへInstantを保存できること");

      final Instant actual =
          dslContext
              .select(EVENT_PUBLICATION.PUBLICATION_DATE)
              .from(EVENT_PUBLICATION)
              .where(EVENT_PUBLICATION.ID.eq(publicationId))
              .fetchOptional(EVENT_PUBLICATION.PUBLICATION_DATE)
              .orElseThrow();

      assertEquals(expected, actual, "生成jOOQフィールドがInstantを維持すること");
    } finally {
      dslContext
          .deleteFrom(EVENT_PUBLICATION)
          .where(EVENT_PUBLICATION.ID.eq(publicationId))
          .execute();
    }
  }

  @Test
  void authorizationRequestUsesPkceS256() {
    final MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/web");
    request.setServletPath("/oauth2/authorization/web");

    final OAuth2AuthorizationRequest authorizationRequest =
        authorizationRequestResolver.resolve(request);

    assertNotNull(authorizationRequest, "認可リクエストが解決されること");
    assertNotNull(
        authorizationRequest.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE),
        "PKCE の code_challenge が付与されること");
    assertEquals(
        "S256",
        authorizationRequest
            .getAdditionalParameters()
            .get(PkceParameterNames.CODE_CHALLENGE_METHOD),
        "PKCE の code_challenge_method が S256 であること");
    assertNotNull(
        authorizationRequest.getAttributes().get(PkceParameterNames.CODE_VERIFIER),
        "PKCE の code_verifier が保持されること");
  }

  /** OIDC クライアント登録をテスト用のダミー発行者で差し替える構成。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class OidcTestConfiguration {

    @Bean
    /* package */ ClientRegistrationRepository clientRegistrationRepository() {
      final ClientRegistration registration =
          ClientRegistration.withRegistrationId("web")
              .clientId("test-web-client")
              .clientSecret("test-web-client-secret")
              .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
              .scope("openid", "profile", "email")
              .authorizationUri("https://issuer.example.test/oauth2/authorize")
              .tokenUri("https://issuer.example.test/oauth2/token")
              .jwkSetUri("https://issuer.example.test/oauth2/jwks")
              .userInfoUri("https://issuer.example.test/oauth2/userinfo")
              .userNameAttributeName("sub")
              .clientName("Test Web Client")
              .build();
      return new InMemoryClientRegistrationRepository(registration);
    }
  }
}
