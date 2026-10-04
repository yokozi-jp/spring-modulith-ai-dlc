package com.example.demo;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

/** OpenAPI 3.1 文書へ全 API が共有する schema と response を登録する。 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  /**
   * 契約の版を手で管理する。
   *
   * <p>互換な追加で MINOR、破壊的変更で MAJOR を上げ、アプリのリリース版と連動させない（docs/web-api/versioning.md）。
   */
  /* package */ static final String CONTRACT_VERSION = "0.1.0";

  /** 共通の Problem response を参照する接頭辞。 */
  private static final String PROBLEM_RESPONSE_REF = "#/components/responses/";

  /** API の基本情報と RFC 9457 共通 components を定義する。 */
  @Bean
  public OpenAPI openApi() {
    final Components components =
        new Components()
            .addSchemas("ProblemDetail", problemDetailSchema())
            .addResponses("BadRequestProblem", problemResponse("要求の形式または入力値が正しくない"))
            .addResponses("UnauthorizedProblem", problemResponse("認証されていない"))
            .addResponses("ForbiddenProblem", problemResponse("操作の権限がない"))
            .addResponses("NotFoundProblem", problemResponse("対象のリソースが存在しない"))
            .addResponses("ConflictProblem", problemResponse("リソースの現在の状態と競合する"))
            .addResponses("UnprocessableContentProblem", problemResponse("業務規則に反するため処理できない"))
            .addResponses("InternalServerErrorProblem", problemResponse("サーバーの内部エラー"));
    return new OpenAPI()
        .info(
            new Info()
                .title("Demo API")
                .description("Demo アプリケーションの HTTP API")
                .version(CONTRACT_VERSION))
        .servers(List.of(new Server().url("/").description("現在のオリジン")))
        .components(components);
  }

  /**
   * 全 operation に 401、403、500 の共通 response を付け、入力のある operation にだけ 400 を付ける。
   *
   * <p>operation ごとに書いた同じ status の response は上書きしない。
   */
  @Bean
  public OpenApiCustomizer commonProblemResponses() {
    return openApi -> forEachOperation(openApi, OpenApiConfig::addCommonProblemResponses);
  }

  /**
   * Javadoc から作った summary と description を整える。
   *
   * <p>summary の前後の空白を除き、description から重複する summary と先頭の {@code <p>} を除く。
   */
  @Bean
  public OpenApiCustomizer trimJavadocSummaries() {
    return openApi -> forEachOperation(openApi, OpenApiConfig::trimJavadoc);
  }

  /** 文書の全 operation に処理を適用する。paths がなければ何もしない。 */
  private static void forEachOperation(final OpenAPI openApi, final Consumer<Operation> action) {
    final Map<String, PathItem> paths = openApi.getPaths();
    if (paths == null) {
      return;
    }
    for (final PathItem pathItem : paths.values()) {
      pathItem.readOperations().forEach(action);
    }
  }

  /** 1 つの operation の Javadoc 由来の summary と description を整える。 */
  private static void trimJavadoc(final Operation operation) {
    final String summary = operation.getSummary();
    if (summary == null) {
      return;
    }
    final String trimmedSummary = summary.strip();
    operation.setSummary(trimmedSummary);

    final String description = operation.getDescription();
    if (description == null || !description.startsWith(trimmedSummary)) {
      return;
    }
    String details = description.substring(trimmedSummary.length()).stripLeading();
    if (details.startsWith("<p>")) {
      details = details.substring(3).stripLeading();
    }
    operation.setDescription(details);
  }

  /** 1 つの operation へ共通の Problem response を足す。 */
  private static void addCommonProblemResponses(final Operation operation) {
    final Map<String, ApiResponse> responses = operation.getResponses();
    final boolean hasInput =
        (operation.getParameters() != null && !operation.getParameters().isEmpty())
            || operation.getRequestBody() != null;
    if (hasInput) {
      responses.putIfAbsent("400", problemRef("BadRequestProblem"));
    }
    responses.putIfAbsent("401", problemRef("UnauthorizedProblem"));
    responses.putIfAbsent("403", problemRef("ForbiddenProblem"));
    responses.putIfAbsent("500", problemRef("InternalServerErrorProblem"));
  }

  /** components の共通 Problem response を参照する response を作る。 */
  private static ApiResponse problemRef(final String name) {
    return new ApiResponse().$ref(PROBLEM_RESPONSE_REF + name);
  }

  /** RFC 9457 の Problem Details の schema を作る。 */
  private static Schema<?> problemDetailSchema() {
    final ObjectSchema schema = new ObjectSchema();
    schema.setDescription("RFC 9457 の Problem Details");
    schema.setRequired(List.of("type", "title", "status"));
    schema.addProperty(
        "type",
        new StringSchema()
            .format("uri")
            .description("Problem type の安定した識別 URI")
            .example("about:blank"));
    schema.addProperty("title", new StringSchema().description("Problem type の短い説明"));
    schema.addProperty(
        "status", new IntegerSchema().format("int32").description("実際の HTTP status code"));
    schema.addProperty("detail", new StringSchema().description("この失敗に固有の、人が読むための説明"));
    schema.addProperty(
        "instance", new StringSchema().format("uri-reference").description("失敗発生を識別する URI"));
    return schema;
  }

  /** application/problem+json で ProblemDetail を返す response を作る。 */
  private static ApiResponse problemResponse(final String description) {
    final io.swagger.v3.oas.models.media.MediaType mediaType =
        new io.swagger.v3.oas.models.media.MediaType()
            .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"));
    return new ApiResponse()
        .description(description)
        .content(new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE, mediaType));
  }
}
