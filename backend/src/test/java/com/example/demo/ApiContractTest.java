package com.example.demo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** API の Problem Details とブラウザ向けセキュリティヘッダを HTTP レベルで検証する。 */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class ApiContractTest {

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  void unauthenticatedApiRequestReturnsProblemDetails() throws Exception {
    mockMvc
        .perform(get("/api/missing"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").value("Unauthorized"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void csrfFailureReturnsProblemDetails() throws Exception {
    mockMvc
        .perform(post("/api/missing").with(user("test-user")))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").value("Forbidden"))
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  void missingAuthenticatedApiResourceUsesMvcProblemDetails() throws Exception {
    mockMvc
        .perform(get("/api/missing").with(user("test-user")))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void errorEndpointReturnsProblemDetailsWithoutImplementationDetails() throws Exception {
    mockMvc
        .perform(
            get("/error")
                .requestAttr(
                    RequestDispatcher.ERROR_STATUS_CODE,
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR.value())
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION,
                    new IllegalStateException("sensitive implementation detail")))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").value("Internal Server Error"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.detail").doesNotExist())
        .andExpect(jsonPath("$.exception").doesNotExist())
        .andExpect(jsonPath("$.trace").doesNotExist());
  }

  @Test
  void crossOriginBrowserClientIsNotAllowed() throws Exception {
    mockMvc
        .perform(get("/api/missing").header("Origin", "https://client.example"))
        .andExpect(status().isUnauthorized())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void browserSecurityHeadersAreExplicit() throws Exception {
    mockMvc
        .perform(get("/error").secure(true))
        .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
        .andExpect(
            header()
                .string(
                    "Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(
            header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
  }
}
