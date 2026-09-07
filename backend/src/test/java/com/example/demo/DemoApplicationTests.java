package com.example.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;

@SpringBootTest
@Import(DemoApplicationTests.OidcTestConfiguration.class)
class DemoApplicationTests {

  @Autowired private Clock clock;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private OAuth2AuthorizationRequestResolver authorizationRequestResolver;

  @Test
  void contextLoads() {}

  @Test
  void applicationClockUsesUtc() {
    assertEquals(ZoneOffset.UTC, clock.getZone());
  }

  @Test
  void databaseSessionUsesUtc() {
    assertEquals("UTC", jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class));
  }

  @Test
  void instantRoundTripsThroughTimestampWithTimeZone() {
    final var expected = Instant.parse("2026-09-07T06:18:42.567123Z");
    final OffsetDateTime actual =
        jdbcTemplate.queryForObject(
            "SELECT CAST(? AS TIMESTAMP WITH TIME ZONE)",
            OffsetDateTime.class,
            expected.atOffset(ZoneOffset.UTC));

    assertNotNull(actual);
    assertEquals(expected, actual.toInstant());
  }

  @Test
  void authorizationRequestUsesPkceS256() {
    final var request = new MockHttpServletRequest("GET", "/oauth2/authorization/web");
    request.setServletPath("/oauth2/authorization/web");

    final var authorizationRequest = authorizationRequestResolver.resolve(request);

    assertNotNull(authorizationRequest);
    assertNotNull(
        authorizationRequest.getAdditionalParameters().get(PkceParameterNames.CODE_CHALLENGE));
    assertEquals(
        "S256",
        authorizationRequest
            .getAdditionalParameters()
            .get(PkceParameterNames.CODE_CHALLENGE_METHOD));
    assertNotNull(authorizationRequest.getAttributes().get(PkceParameterNames.CODE_VERIFIER));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class OidcTestConfiguration {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
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
