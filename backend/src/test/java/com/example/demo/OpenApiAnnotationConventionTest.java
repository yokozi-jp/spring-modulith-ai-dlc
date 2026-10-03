package com.example.demo;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.testkit.SharedTestConfiguration;
import java.util.List;
import java.util.Map;
import openapifixture.sampleorder.presentation.web.SampleOrderOpenApiFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * サンプル Controller から springdoc が作る OpenAPI 文書が、アノテーションと Javadoc の規約どおりになることを検証する（ADR-052）。
 *
 * <p>サンプル Controller は com.example.demo の外にあり、このテストだけが読み込む。exportOpenApi が書き出す契約には含まれない。
 */
// MockMvc.perform が Exception を宣言するため、補助メソッドも Exception を宣言する。
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.UnitTestShouldIncludeAssert"})
@SpringBootTest
@AutoConfigureMockMvc
@Import({SharedTestConfiguration.class, SampleOrderOpenApiFixture.class})
class OpenApiAnnotationConventionTest {

  /** 注文の受付。 */
  private static final String PLACE = "$.paths['/api/sample-orders'].post";

  /** 注文の一覧。 */
  private static final String LIST = "$.paths['/api/sample-orders'].get";

  /** 注文の詳細。 */
  private static final String FIND = "$.paths['/api/sample-orders/{sampleOrderId}'].get";

  /** 注文の取消。 */
  private static final String CANCEL = "$.paths['/api/sample-orders/{sampleOrderId}/cancel'].post";

  /** サンプル Controller の全 operation。 */
  private static final List<String> OPERATIONS = List.of(PLACE, LIST, FIND, CANCEL);

  /** 入力（parameter か requestBody）を持つ operation。 */
  private static final List<String> OPERATIONS_WITH_INPUT = List.of(PLACE, FIND, CANCEL);

  /** operation ごとに明示した operationId。 */
  private static final Map<String, String> OPERATION_IDS =
      Map.of(
          PLACE, "placeSampleOrder",
          LIST, "listSampleOrders",
          FIND, "findSampleOrderById",
          CANCEL, "cancelSampleOrder");

  /** operation ごとのハンドラの Javadoc の 1 行目。 */
  private static final Map<String, String> SUMMARIES =
      Map.of(
          PLACE, "サンプル注文を受け付ける。",
          LIST, "サンプル注文の一覧を返す。",
          FIND, "サンプル注文の詳細を返す。",
          CANCEL, "サンプル注文を取り消す。");

  /** 実行時に生成される OpenAPI endpoint を呼び出すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("tag は @Tag の説明を持ち、各 operation に 1 つだけ付き、operationId は明示した値になる")
  void tagAndOperationIdComeFromAnnotations() throws Exception {
    final ResultActions apiDocs =
        apiDocs()
            .andExpect(
                jsonPath("$.tags[?(@.name == 'sample-order')].description")
                    .value(contains("サンプル注文の API（OpenAPI 生成の検証用）")));
    for (final String operation : OPERATIONS) {
      apiDocs.andExpect(jsonPath(operation + ".tags").value(contains("sample-order")));
    }
    for (final Map.Entry<String, String> operationId : OPERATION_IDS.entrySet()) {
      apiDocs.andExpect(
          jsonPath(operationId.getKey() + ".operationId").value(operationId.getValue()));
    }
  }

  // ponytail: springdoc 3.1.1 の Javadoc の扱いを固定する。版を上げて変わったら、規約文書と一緒に直す。
  @Test
  @DisplayName("package-private のハンドラの Javadoc の 1 行目が summary に、本文全体が description になる")
  void summaryAndDescriptionComeFromHandlerJavadoc() throws Exception {
    final ResultActions apiDocs = apiDocs();
    for (final Map.Entry<String, String> summary : SUMMARIES.entrySet()) {
      apiDocs.andExpect(jsonPath(summary.getKey() + ".summary").value(summary.getValue()));
    }
    apiDocs
        // description は summary の文と <p> の文字列を含んだまま出る。
        .andExpect(
            jsonPath(PLACE + ".description")
                .value("サンプル注文を受け付ける。\n\n <p>受け付けた注文の URI を Location に入れて返す。"))
        .andExpect(jsonPath(FIND + ".description").value("サンプル注文の詳細を返す。\n\n <p>注文がなければ 404 を返す。"));
  }

  @Test
  @DisplayName("ハンドラの @param と @return が parameter、requestBody、成功の応答の説明になる")
  void parameterAndResponseDescriptionsComeFromJavadocTags() throws Exception {
    apiDocs()
        .andExpect(jsonPath(FIND + ".parameters[0].name").value("sampleOrderId"))
        .andExpect(jsonPath(FIND + ".parameters[0].description").value("サンプル注文の ID"))
        .andExpect(jsonPath(PLACE + ".requestBody.description").value("受け付ける注文の内容"))
        .andExpect(jsonPath(FIND + ".responses['200'].description").value("サンプル注文の詳細"))
        .andExpect(
            jsonPath(FIND + ".responses['200'].content['application/json'].schema['$ref']")
                .value("#/components/schemas/SampleOrderResponse"))
        .andExpect(jsonPath(LIST + ".responses['200'].description").value("サンプル注文の一覧"));
  }

  @Test
  @DisplayName("全 operation に 401、403、500 が付き、400 は入力のある operation にだけ付く")
  void commonProblemResponsesAreAttachedByCustomizer() throws Exception {
    final ResultActions apiDocs = apiDocs();
    for (final String operation : OPERATIONS) {
      apiDocs
          .andExpect(problemRef(operation, "401", "UnauthorizedProblem"))
          .andExpect(problemRef(operation, "403", "ForbiddenProblem"))
          .andExpect(problemRef(operation, "500", "InternalServerErrorProblem"));
    }
    for (final String operation : OPERATIONS_WITH_INPUT) {
      apiDocs.andExpect(problemRef(operation, "400", "BadRequestProblem"));
    }
    apiDocs.andExpect(jsonPath(LIST + ".responses['400']").doesNotExist());
  }

  @Test
  @DisplayName("operation ごとに書いた 404、409、422 は共通の Problem response を参照する")
  void operationSpecificProblemResponsesReferenceComponents() throws Exception {
    apiDocs()
        .andExpect(problemRef(FIND, "404", "NotFoundProblem"))
        .andExpect(problemRef(CANCEL, "409", "ConflictProblem"))
        .andExpect(problemRef(PLACE, "422", "UnprocessableContentProblem"));
  }

  @Test
  @DisplayName("作成は 201 と Location、本文のない更新は 204 だけを返し、200 を残さない")
  void createdAndNoContentReplaceDefaultOk() throws Exception {
    apiDocs()
        .andExpect(jsonPath(PLACE + ".responses['201'].description").value("本文のない 201 の応答"))
        .andExpect(
            jsonPath(PLACE + ".responses['201'].headers.Location.description")
                .value("作成したサンプル注文の URI"))
        .andExpect(
            jsonPath(PLACE + ".responses['201'].headers.Location.schema.format").value("uri"))
        .andExpect(jsonPath(PLACE + ".responses['201'].content").doesNotExist())
        .andExpect(jsonPath(PLACE + ".responses['200']").doesNotExist())
        .andExpect(jsonPath(CANCEL + ".responses['204'].description").value("本文のない 204 の応答"))
        .andExpect(jsonPath(CANCEL + ".responses['204'].content").doesNotExist())
        .andExpect(jsonPath(CANCEL + ".responses['200']").doesNotExist());
  }

  @Test
  @DisplayName("record の Javadoc と @param が schema の説明に、@Schema の example が example になる")
  void schemaDescriptionsAndExamplesComeFromRecords() throws Exception {
    final String response = "$.components.schemas.SampleOrderResponse";
    final String request = "$.components.schemas.SampleOrderRequest";
    apiDocs()
        .andExpect(jsonPath(response + ".description").value("サンプル注文の詳細。"))
        .andExpect(jsonPath(response + ".properties.sampleOrderId.description").value("サンプル注文の ID"))
        .andExpect(jsonPath(response + ".properties.sampleOrderId.example").value("SO-0001"))
        // swagger-core は OpenAPI 3.1 でも examples の配列でなく example を出す。
        .andExpect(jsonPath(request + ".properties.customerId.example").value("C-0001"))
        .andExpect(jsonPath(request + ".properties.customerId.examples").doesNotExist())
        .andExpect(jsonPath(request + ".properties.quantity.example").value(2))
        .andExpect(jsonPath(request + ".properties.giftWrap.description").value("ギフト包装をするかどうか"));
  }

  private ResultActions apiDocs() throws Exception {
    return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  private static ResultMatcher problemRef(
      final String operation, final String status, final String response) {
    return jsonPath(operation + ".responses['" + status + "']['$ref']")
        .value("#/components/responses/" + response);
  }
}
