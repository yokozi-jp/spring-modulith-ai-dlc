package com.example.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@link CrossOriginRequestFilter} が Security filter chain の中で {@code CsrfFilter} の前に動き、拒否を専用の type
 * の Problem Details で返すことを検証する（ADR-134）。
 *
 * <p>どの要求を拒否し、どれを通すかは {@link CrossOriginRequestFilterTest} が確かめる。
 */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.UnitTestShouldIncludeAssert"})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class CrossOriginRequestContractTest {

  /** 別のオリジンからの更新を拒否した 403 の problem type。 */
  private static final String CROSS_ORIGIN_REQUEST_REJECTED =
      "/problems/cross-origin-request-rejected";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "ja, 別のオリジンからの更新は受け付けません",
    "en, Cross-origin update request rejected",
  })
  @DisplayName("別のオリジンからの更新は、CSRF のトークンが正しくても、専用の type の 403 Problem Details を返す")
  void crossOriginUpdateWithValidTokenReturnsProblemDetails(
      final String language, final String title) throws Exception {
    mockMvc
        .perform(
            update("https://evil.localhost")
                .with(csrf())
                .header(HttpHeaders.ACCEPT_LANGUAGE, language))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value(CROSS_ORIGIN_REQUEST_REJECTED))
        .andExpect(jsonPath("$.title").value(title))
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.detail").doesNotExist());
  }

  @Test
  @DisplayName("別のオリジンからの CSRF のトークンのない更新は、CSRF の失敗より先に、専用の type の 403 になる")
  void crossOriginUpdateWithoutTokenIsRejectedBeforeCsrfCheck() throws Exception {
    mockMvc
        .perform(update("https://evil.localhost"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(CROSS_ORIGIN_REQUEST_REJECTED));
  }

  @Test
  @DisplayName("同じオリジンからの CSRF のトークンの正しい更新は、拒否されずに認証の検査まで進み、401 になる")
  void sameOriginUpdateWithValidTokenReachesAuthentication() throws Exception {
    mockMvc
        .perform(
            post("/api/orders")
                .with(csrf())
                .header(HttpHeaders.ORIGIN, "http://localhost")
                .header("Sec-Fetch-Site", "same-origin"))
        .andExpect(status().isUnauthorized());
  }

  /** ログインした利用者が {@code origin} から送る、注文の作成の要求。 */
  private static MockHttpServletRequestBuilder update(final String origin) {
    return post("/api/orders").with(user("test-user")).header(HttpHeaders.ORIGIN, origin);
  }
}
