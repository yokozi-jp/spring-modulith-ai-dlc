package com.example.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ALB が付ける X-Forwarded-* を解釈し、OIDC の redirect_uri を外から見える https のホストで組み立てることを検証する。
 *
 * <p>{@code server.request.header.forwarded.strategy} のキーを誤ると、既定の none に戻り、redirect_uri が内部の http
 * のホストになるのに、ほかのテストは失敗しない。
 */
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class ForwardedHeadersTest {

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("X-Forwarded-Proto と X-Forwarded-Host から、OIDC の redirect_uri を組み立てる")
  void buildsRedirectUriFromForwardedHeaders() throws Exception {
    mockMvc
        .perform(
            get("/oauth2/authorization/web")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "app.example.com")
                .header("X-Forwarded-Port", "443"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header()
                .string(
                    HttpHeaders.LOCATION,
                    org.hamcrest.Matchers.containsString(
                        "redirect_uri=https://app.example.com/login/oauth2/code/web")));
  }
}
