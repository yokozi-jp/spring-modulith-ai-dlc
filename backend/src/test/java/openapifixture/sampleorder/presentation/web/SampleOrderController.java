package openapifixture.sampleorder.presentation.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** サンプル注文の HTTP API。OpenAPI の生成結果を検証するためだけに使い、固定値を返す。 */
@RestController
@RequestMapping("/api/sample-orders")
@Tag(name = "sample-order", description = "サンプル注文の API（OpenAPI 生成の検証用）")
class SampleOrderController {

  /**
   * サンプル注文を受け付ける。
   *
   * <p>受け付けた注文の URI を Location に入れて返す。
   *
   * @param request 受け付ける注文の内容
   * @return 本文のない 201 の応答
   */
  @Operation(operationId = "placeSampleOrder")
  @ApiResponse(
      responseCode = "201",
      headers =
          @Header(
              name = "Location",
              description = "作成したサンプル注文の URI",
              schema = @Schema(type = "string", format = "uri")))
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PostMapping
  /* package */ ResponseEntity<Void> place(@Valid @RequestBody final SampleOrderRequest request) {
    return ResponseEntity.created(URI.create("/api/sample-orders/SO-0001")).build();
  }

  /**
   * サンプル注文の詳細を返す。
   *
   * <p>注文がなければ 404 を返す。
   *
   * @param sampleOrderId サンプル注文の ID
   * @return サンプル注文の詳細
   */
  @Operation(operationId = "findSampleOrderById")
  // @ApiResponse を 1 つでも書くと springdoc は成功の応答を足さないため、200 も書く。
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @GetMapping("/{sampleOrderId}")
  /* package */ SampleOrderResponse details(@PathVariable final String sampleOrderId) {
    return new SampleOrderResponse(
        sampleOrderId, "PLACED", List.of(new SampleOrderResponse.OrderLineResponse("P-0001", 2)));
  }

  /**
   * サンプル注文を取り消す。
   *
   * <p>取り消せない状態なら 409 を返す。
   *
   * @param sampleOrderId 取り消すサンプル注文の ID
   * @return 本文のない 204 の応答
   */
  @Operation(operationId = "cancelSampleOrder")
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @PostMapping("/{sampleOrderId}/cancel")
  /* package */ ResponseEntity<Void> cancel(@PathVariable final String sampleOrderId) {
    return ResponseEntity.noContent().build();
  }

  /**
   * サンプル注文の一覧を返す。
   *
   * <p>一覧は items で包んで返す。
   *
   * @return サンプル注文の一覧
   */
  @Operation(operationId = "listSampleOrders")
  @GetMapping
  /* package */ SampleOrderListResponse search() {
    return new SampleOrderListResponse(List.of());
  }
}
