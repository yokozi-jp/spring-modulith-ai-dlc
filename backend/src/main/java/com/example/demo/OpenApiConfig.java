package com.example.demo;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** OpenAPI 3.1 文書へ全 API が共有する schema と response を登録する。 */
// 全 API が共有する components の組み立てを 1 か所に置くため、schema ごとの小さなメソッドが増える。
@SuppressWarnings("PMD.TooManyMethods")
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  /**
   * 契約の版を手で管理する。
   *
   * <p>互換な追加で MINOR、破壊的変更で MAJOR を上げ、アプリのリリース版と連動させない（docs/web-api/versioning.md）。
   */
  /* package */ static final String CONTRACT_VERSION = "0.5.0";

  /** 共通の Problem response を参照する接頭辞。 */
  private static final String PROBLEM_RESPONSE_REF = "#/components/responses/";

  /** components の schema を参照する接頭辞。 */
  private static final String SCHEMA_REF = "#/components/schemas/";

  /** RFC 9457 の共通 schema の名前。 */
  private static final String PROBLEM_DETAIL = "ProblemDetail";

  /** API の基本情報と RFC 9457 共通 components を定義する。 */
  @Bean
  public OpenAPI openApi() {
    final Components components =
        new Components()
            .addSchemas(PROBLEM_DETAIL, problemDetailSchema())
            .addSchemas("ValidationError", validationErrorSchema())
            .addSchemas("ValidationProblem", validationProblemSchema())
            .addResponses(
                "BadRequestProblem", problemResponse("要求の形式または入力値が正しくない", "ValidationProblem"))
            .addResponses(
                "UnauthorizedProblem",
                problemResponse("認証されていない", PROBLEM_DETAIL)
                    .addHeaderObject(
                        HttpHeaders.WWW_AUTHENTICATE,
                        new Header()
                            .required(true)
                            .description("認証の challenge。ブラウザの Cookie セッションを表す独自 scheme（ADR-059）")
                            .schema(new StringSchema().example("Session realm=\"demo\""))))
            .addResponses("ForbiddenProblem", problemResponse("操作の権限がない", PROBLEM_DETAIL))
            .addResponses("NotFoundProblem", problemResponse("対象のリソースが存在しない", PROBLEM_DETAIL))
            .addResponses("ConflictProblem", problemResponse("リソースの現在の状態と競合する", PROBLEM_DETAIL))
            .addResponses(
                "UnprocessableContentProblem", problemResponse("業務規則に反するため処理できない", PROBLEM_DETAIL))
            .addResponses(
                "InternalServerErrorProblem", problemResponse("サーバーの内部エラー", PROBLEM_DETAIL));
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
            .format("uri-reference")
            .description("Problem type の識別 URI。about:blank かパスを全部書いた相対 URI")
            .example("about:blank"));
    schema.addProperty("title", new StringSchema().description("Problem type の短い説明"));
    schema.addProperty(
        "status", new IntegerSchema().format("int32").description("実際の HTTP status code"));
    schema.addProperty("detail", new StringSchema().description("この失敗に固有の、人が読むための説明"));
    schema.addProperty(
        "instance", new StringSchema().format("uri-reference").description("失敗発生を識別する URI"));
    return schema;
  }

  /** 入力検証の誤り 1 件の schema を作る。 */
  private static Schema<?> validationErrorSchema() {
    final ObjectSchema schema = new ObjectSchema();
    schema.setDescription("入力検証の誤り");
    schema.setRequired(List.of("pointer", "detail"));
    schema.addProperty(
        "pointer",
        new StringSchema()
            .description("誤りのある入力の位置を示す RFC 6901 の JSON Pointer")
            .example("/items/0/name"));
    schema.addProperty(
        "detail", new StringSchema().description("利用者向けの誤りの説明。入力値を含まない").example("この項目は必須です。"));
    return schema;
  }

  /** 入力検証の誤りを errors に持つ Problem Details の schema を作る。 */
  private static Schema<?> validationProblemSchema() {
    final ObjectSchema extension = new ObjectSchema();
    extension.addProperty(
        "type",
        new StringSchema()
            .format("uri-reference")
            .description("入力検証エラーなら /problems/validation-error。それ以外の 400 は about:blank")
            .example("/problems/validation-error"));
    extension.addProperty(
        "errors",
        new ArraySchema()
            .items(new Schema<>().$ref(SCHEMA_REF + "ValidationError"))
            .description("入力検証の誤り。type が /problems/validation-error のときだけ必ず含む"));
    return new Schema<>()
        .description(
            "400 の Problem Details。"
                + "type が /problems/validation-error なら入力検証エラーで、errors に誤りのある入力の位置と説明を持つ")
        .allOf(List.of(new Schema<>().$ref(SCHEMA_REF + PROBLEM_DETAIL), extension));
  }

  /** application/problem+json で指定した schema を返す response を作る。 */
  private static ApiResponse problemResponse(final String description, final String schemaName) {
    final io.swagger.v3.oas.models.media.MediaType mediaType =
        new io.swagger.v3.oas.models.media.MediaType()
            .schema(new Schema<>().$ref(SCHEMA_REF + schemaName));
    return new ApiResponse()
        .description(description)
        .content(new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE, mediaType));
  }
}
