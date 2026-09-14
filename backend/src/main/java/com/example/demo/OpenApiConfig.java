package com.example.demo;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;

/** OpenAPI 3.1 文書へ全 API が共有する schema と response を登録する。 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

  /** API の基本情報と RFC 9457 共通 components を定義する。 */
  @Bean
  public OpenAPI openApi() {
    final Components components =
        new Components()
            .addSchemas("ProblemDetail", problemDetailSchema())
            .addResponses("BadRequestProblem", problemResponse("Bad Request"))
            .addResponses("UnauthorizedProblem", problemResponse("Unauthorized"))
            .addResponses("ForbiddenProblem", problemResponse("Forbidden"))
            .addResponses("InternalServerErrorProblem", problemResponse("Internal Server Error"));
    return new OpenAPI()
        .info(
            new Info().title("Demo API").description("Demo application HTTP API").version("0.0.1"))
        .components(components);
  }

  private static Schema<?> problemDetailSchema() {
    final ObjectSchema schema = new ObjectSchema();
    schema.setDescription("RFC 9457 Problem Details");
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

  private static ApiResponse problemResponse(final String description) {
    final io.swagger.v3.oas.models.media.MediaType mediaType =
        new io.swagger.v3.oas.models.media.MediaType()
            .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"));
    return new ApiResponse()
        .description(description)
        .content(new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE, mediaType));
  }
}
