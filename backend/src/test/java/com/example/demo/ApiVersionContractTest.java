package com.example.demo;

import static com.example.demo.testkit.AppUsers.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/v{version}/...} のバージョンが、対応するバージョンだけを通し、それ以外を 400 の Problem Details
 * にすることを確かめる（ADR-138）。
 */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.UnitTestShouldIncludeAssert"})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class ApiVersionContractTest {

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("v1 の API は処理され、同じ API のバージョンのない URL は 404 になる")
  void versionedPathIsServedAndUnversionedPathIsNotFound() throws Exception {
    mockMvc.perform(get("/api/v1/memberships").with(oidcLogin())).andExpect(status().isOk());

    mockMvc.perform(get("/api/memberships").with(oidcLogin())).andExpect(status().isNotFound());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "/api/v2/memberships",
        "/api/v0/memberships",
        "/api/v1.1/memberships",
        "/api/v1.0/memberships",
        "/api/v01/memberships",
        "/api/vX1/memberships",
        "/api/vv1/memberships",
        "/api/v/memberships"
      })
  @DisplayName("v1 でも対応するバージョンでもない区間は、v1 として読まれず 400 の Problem Details になる")
  void unsupportedVersionIsRejected(final String path) throws Exception {
    mockMvc
        .perform(get(path).with(oidcLogin()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.detail").doesNotExist())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  @DisplayName("先頭の区間をパーセントエンコードしても、バージョンの検査を逃れない")
  void percentEncodedApiSegmentIsStillValidated() throws Exception {
    mockMvc
        .perform(get(URI.create("/%61pi/v2/memberships")).with(oidcLogin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  @DisplayName("対応するバージョンの下に存在しないパスは 404 になり、API の外のパスはバージョンを求めない")
  void missingResourceUnderSupportedVersionIsNotFound() throws Exception {
    mockMvc.perform(get("/api/v1/missing").with(oidcLogin())).andExpect(status().isNotFound());
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }
}
