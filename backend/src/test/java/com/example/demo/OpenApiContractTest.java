package com.example.demo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hamcrest.Matcher;
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
 * 生成された OpenAPI 文書の版、共通 components、Idempotency-Key を求める operation を検証する。
 *
 * <p>{@code openapi.output} が指定されたときは、springdoc の YAML endpoint の出力をそのパスへ書き出す。
 *
 * <p>指定するのは、{@code -PopenapiExport=true} を付けた {@code test} である。
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
  @DisplayName("生成 OpenAPI は 3.1 で契約の版、RFC 9457 と Idempotency-Key の共通 components を含む")
  void openApiDocumentContainsProblemDetailsComponents() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.openapi").value(startsWith("3.1.")))
        .andExpect(jsonPath("$.info.version").value(OpenApiConfig.CONTRACT_VERSION))
        .andExpect(jsonPath("$.servers[0].url").value("/"))
        .andExpect(jsonPath("$.servers[0].description").value("現在のオリジン"))
        .andExpect(jsonPath("$.components.schemas.ProblemDetail.type").value("object"))
        .andExpect(jsonPath("$.components.schemas.ProblemDetail.required.length()").value(3))
        .andExpect(problemResponseExists("BadRequestProblem"))
        .andExpect(problemResponseExists("UnauthorizedProblem"))
        .andExpect(problemResponseExists("ForbiddenProblem"))
        .andExpect(problemResponseExists("NotFoundProblem"))
        .andExpect(problemResponseExists("ConflictProblem"))
        .andExpect(problemResponseExists("UnprocessableContentProblem"))
        .andExpect(problemResponseExists("InternalServerErrorProblem"))
        .andExpect(
            jsonPath(
                    "$.components.responses.BadRequestProblem.content['application/problem+json'].schema['$ref']")
                .value("#/components/schemas/ValidationProblem"))
        .andExpect(jsonPath("$.components.schemas.ValidationProblem").exists())
        .andExpect(jsonPath("$.components.schemas.ValidationError").exists())
        .andExpect(
            jsonPath("$.components.schemas.ProblemDetail.properties.type.format")
                .value("uri-reference"))
        .andExpect(jsonPath("$.components.parameters.IdempotencyKey.name").value("Idempotency-Key"))
        .andExpect(jsonPath("$.components.parameters.IdempotencyKey.in").value("header"))
        .andExpect(jsonPath("$.components.parameters.IdempotencyKey.required").value(true))
        .andExpect(
            jsonPath("$.components.responses.ConflictProblem.headers['Retry-After']").exists());

    exportWhenRequested();
  }

  @Test
  @DisplayName("GET と HEAD 以外の operation は Idempotency-Key を参照し、印で除くのは selectTenant だけである")
  void unsafeOperationsRequireIdempotencyKey() throws Exception {
    final String json =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    final Set<PathItem.HttpMethod> unsafe =
        EnumSet.of(
            PathItem.HttpMethod.POST,
            PathItem.HttpMethod.PUT,
            PathItem.HttpMethod.PATCH,
            PathItem.HttpMethod.DELETE);
    final List<String> exempt = new ArrayList<>();
    for (final Map.Entry<String, PathItem> path :
        Json31.mapper().readValue(json, OpenAPI.class).getPaths().entrySet()) {
      for (final Map.Entry<PathItem.HttpMethod, Operation> entry :
          path.getValue().readOperationsMap().entrySet()) {
        final Operation operation = entry.getValue();
        final boolean marked =
            operation.getExtensions() != null
                && "none".equals(operation.getExtensions().get("x-idempotency-key"));
        if (marked) {
          exempt.add(operation.getOperationId());
        }
        final List<String> refs =
            operation.getParameters() == null
                ? List.of()
                : operation.getParameters().stream().map(Parameter::get$ref).toList();
        final Matcher<Iterable<? super String>> referencesKey =
            hasItem("#/components/parameters/IdempotencyKey");
        assertThat(
            entry.getKey() + " " + path.getKey(),
            refs,
            unsafe.contains(entry.getKey()) && !marked ? referencesKey : not(referencesKey));
      }
    }
    assertThat("x-idempotency-key: none の operation", exempt, contains("selectTenant"));
  }

  @Test
  @DisplayName("ProblemDetail の type の x-extensible-enum は、about:blank、競合の種類、理由の problem type を持つ")
  void problemDetailTypeListsProblemTypes() throws Exception {
    // 一覧の全体は ProblemTypesTest が固定する。ここでは OpenAPI の文書に載ることを確かめる。
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.components.schemas.ProblemDetail.properties.type['x-extensible-enum']",
                hasItems(
                    "about:blank", "/problems/version-conflict", "/problems/ordering/not-draft")));
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
