package com.example.demo.testkit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * 複数の {@code @SpringBootTest} が共有する、OIDC クライアント登録をダミー発行者へ差し替える構成。
 *
 * <p>同一の構成を共有することで、Spring のテストコンテキストキャッシュを再利用し、テストが増えても コンテキストの再ロードを増やさない。
 */
@TestConfiguration(proxyBeanMethods = false)
public class SharedTestConfiguration {

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
