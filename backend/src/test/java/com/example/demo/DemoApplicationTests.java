package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.demo.support.SharedTestConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

/** アプリケーション起動時の日時・DB・OIDC(PKCE) 設定が規約どおりであることを検証する統合テスト。 */
@SpringBootTest
@Import(SharedTestConfiguration.class)
class DemoApplicationTests {

  /** UTC 固定を検証する対象のアプリケーション {@code Clock}。 */
  @Autowired private Clock clock;

  /** DB セッションのタイムゾーンと {@code timestamptz} 往復を検証するための {@code JdbcTemplate}。 */
  @Autowired private JdbcTemplate jdbcTemplate;

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
}
