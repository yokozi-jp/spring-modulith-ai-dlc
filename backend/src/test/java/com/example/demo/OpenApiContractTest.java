package com.example.demo;

import static org.hamcrest.Matchers.startsWith;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 生成された OpenAPI 文書の版と共通 RFC 9457 components を検証する。 */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.UnitTestShouldIncludeAssert"})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class OpenApiContractTest {

  /** 実行時に生成される OpenAPI endpoint を呼び出すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  void openApiDocumentContainsProblemDetailsComponents() throws Exception {
    final MvcResult result =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.openapi").value(startsWith("3.1.")))
            .andExpect(jsonPath("$.components.schemas.ProblemDetail.type").value("object"))
            .andExpect(jsonPath("$.components.schemas.ProblemDetail.required.length()").value(3))
            .andExpect(
                jsonPath(
                        "$.components.responses.UnauthorizedProblem.content['application/problem+json']")
                    .exists())
            .andExpect(
                jsonPath(
                        "$.components.responses.ForbiddenProblem.content['application/problem+json']")
                    .exists())
            .andReturn();

    exportWhenRequested(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private static void exportWhenRequested(final String openApiDocument) throws IOException {
    final @Nullable String output = System.getProperty("openapi.output");
    if (output == null) {
      return;
    }
    final Path outputPath = Path.of(output).toAbsolutePath();
    final @Nullable Path parent = outputPath.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(outputPath, openApiDocument, StandardCharsets.UTF_8);
  }
}
