package com.example.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** Actuator の health のうち、liveness と readiness だけを未認証で公開することを検証する（ADR-061）。 */
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class ActuatorHealthSecurityTest {

  /** health のルート。 */
  private static final String HEALTH_ROOT = "/actuator/health";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
  @DisplayName("未認証でも liveness と readiness の probe は 200 を返す")
  void unauthenticatedProbeReturnsOk(final String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("未認証の health のルートは 401 で拒否する")
  void unauthenticatedHealthRootIsUnauthorized() throws Exception {
    mockMvc
        .perform(get(HEALTH_ROOT))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Session realm=\"demo\""));
  }

  @Test
  @DisplayName("ログイン済みでも health のルートは 403 で拒否する")
  void authenticatedHealthRootIsForbidden() throws Exception {
    mockMvc.perform(get(HEALTH_ROOT).with(user("test-user"))).andExpect(status().isForbidden());
  }
}
