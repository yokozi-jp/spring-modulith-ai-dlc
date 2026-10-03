package com.example.demo;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * 生成された OpenAPI 文書の版と共通 RFC 9457 components を検証する。
 *
 * <p>{@code openapi.output} が指定されたときは、springdoc の YAML endpoint の出力をそのパスへ書き出す（exportOpenApi）。
 */
// MockMvc.perform が Exception を宣言するため、補助メソッドも Exception を宣言する。
@SuppressWarnings({
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class OpenApiContractTest {

  /** 実行時に生成される OpenAPI endpoint を呼び出すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("生成 OpenAPI は 3.1 で契約の版と RFC 9457 共通 components を含む")
  void openApiDocumentContainsProblemDetailsComponents() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.openapi").value(startsWith("3.1.")))
        .andExpect(jsonPath("$.info.version").value(OpenApiConfig.CONTRACT_VERSION))
        .andExpect(jsonPath("$.components.schemas.ProblemDetail.type").value("object"))
        .andExpect(jsonPath("$.components.schemas.ProblemDetail.required.length()").value(3))
        .andExpect(problemResponseExists("BadRequestProblem"))
        .andExpect(problemResponseExists("UnauthorizedProblem"))
        .andExpect(problemResponseExists("ForbiddenProblem"))
        .andExpect(problemResponseExists("NotFoundProblem"))
        .andExpect(problemResponseExists("ConflictProblem"))
        .andExpect(problemResponseExists("UnprocessableContentProblem"))
        .andExpect(problemResponseExists("InternalServerErrorProblem"));

    exportWhenRequested();
  }

  private static ResultMatcher problemResponseExists(final String name) {
    return jsonPath("$.components.responses." + name + ".content['application/problem+json']")
        .exists();
  }

  private void exportWhenRequested() throws Exception {
    final @Nullable String output = System.getProperty("openapi.output");
    if (output == null) {
      return;
    }
    // SecurityConfig は /v3/api-docs.yaml を公開しないため、認証済みの利用者として取得する。
    final String yaml =
        mockMvc
            .perform(get("/v3/api-docs.yaml").with(user("openapi-export")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    write(Path.of(output).toAbsolutePath(), yaml);
  }

  private static void write(final Path outputPath, final String openApiDocument)
      throws IOException {
    final @Nullable Path parent = outputPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(outputPath, openApiDocument, StandardCharsets.UTF_8);
  }
}
