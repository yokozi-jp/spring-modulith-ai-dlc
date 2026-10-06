package com.example.demo.order.presentation.web;

import com.example.demo.order.OrderQueries;
import com.example.demo.order.OrderSearchCriteria;
import com.example.demo.order.application.CancelOrderCommandHandler;
import com.example.demo.order.application.ChangeOrderLinesCommandHandler;
import com.example.demo.order.application.ConfirmOrderCommandHandler;
import com.example.demo.order.application.DraftOrderCommandHandler;
import com.example.demo.order.application.DraftOrderResult;
import com.example.demo.shared.failure.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** 注文の HTTP API。 */
// 起きる応答（404、409、422）をハンドラごとに @ApiResponse で書くため、同じ文字列を繰り返す。
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@RestController
@RequestMapping("/api/orders")
@Tag(name = "order", description = "注文の API")
class OrderController {

  /** 下書きの注文を作る CommandHandler。 */
  private final DraftOrderCommandHandler draftOrder;

  /** 明細を置き換える CommandHandler。 */
  private final ChangeOrderLinesCommandHandler changeOrderLines;

  /** 注文を確定する CommandHandler。 */
  private final ConfirmOrderCommandHandler confirmOrder;

  /** 注文を取り消す CommandHandler。 */
  private final CancelOrderCommandHandler cancelOrder;

  /** 注文の参照。 */
  private final OrderQueries orderQueries;

  /** 依存を受け取る。 */
  /* package */ OrderController(
      final DraftOrderCommandHandler draftOrder,
      final ChangeOrderLinesCommandHandler changeOrderLines,
      final ConfirmOrderCommandHandler confirmOrder,
      final CancelOrderCommandHandler cancelOrder,
      final OrderQueries orderQueries) {
    this.draftOrder = draftOrder;
    this.changeOrderLines = changeOrderLines;
    this.confirmOrder = confirmOrder;
    this.cancelOrder = cancelOrder;
    this.orderQueries = orderQueries;
  }

  /**
   * 下書きの注文を作る。
   *
   * <p>作成した注文の URI を Location に入れて返す。客先注文番号が既にあれば 409、存在しないか販売終了の商品を指定すると 422 を返す。
   *
   * @param request 作る注文の内容
   * @return 本文のない 201 の応答
   */
  @Operation(operationId = "draftOrder")
  @ApiResponse(
      responseCode = "201",
      headers =
          @Header(
              name = "Location",
              description = "作成した注文の URI",
              schema = @Schema(type = "string", format = "uri")))
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PostMapping
  /* package */ ResponseEntity<Void> draft(@Valid @RequestBody final DraftOrderRequest request) {
    final DraftOrderResult result = draftOrder.handle(request.toCommand());
    final URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{orderId}")
            .buildAndExpand(result.orderId())
            .toUri();
    return ResponseEntity.created(location).build();
  }

  /**
   * 注文の一覧を返す。
   *
   * <p>一覧は作成した時刻の新しい順に items で包んで返す。状態を指定すると、その状態の注文だけを返す。
   *
   * @param status 絞り込む注文の状態（DRAFT、CONFIRMED、CANCELLED）。省略するとすべての状態
   * @return 注文の一覧
   */
  @Operation(operationId = "listOrders")
  @GetMapping
  /* package */ OrderSummaryListResponse search(
      @RequestParam(required = false) @Pattern(regexp = "^(DRAFT|CONFIRMED|CANCELLED)$")
          final @Nullable String status) {
    return new OrderSummaryListResponse(
        orderQueries.search(new OrderSearchCriteria(status)).stream()
            .map(OrderSummaryResponse::from)
            .toList());
  }

  /**
   * 注文の詳細を返す。
   *
   * <p>注文がなければ 404 を返す。
   *
   * @param orderId 注文の ID
   * @return 注文の詳細
   */
  @Operation(operationId = "findOrderById")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @GetMapping("/{orderId}")
  /* package */ OrderDetailsResponse details(@PathVariable final UUID orderId) {
    return orderQueries
        .findDetails(orderId.toString())
        .map(OrderDetailsResponse::from)
        .orElseThrow(() -> new NotFoundException("order not found: orderId=" + orderId));
  }

  /**
   * 下書きの注文の明細を置き換える。
   *
   * <p>注文がなければ 404、画面が読んだ後に注文が変わっていれば 409、下書きでないか、存在しないか販売終了の商品を指定すると 422 を返す。
   *
   * @param orderId 明細を置き換える注文の ID
   * @param request 置き換えた後の明細と、画面が読んだ注文のロック番号
   * @return 本文のない 204 の応答
   */
  @Operation(operationId = "changeOrderLines")
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PutMapping("/{orderId}/lines")
  /* package */ ResponseEntity<Void> changeLines(
      @PathVariable final UUID orderId, @Valid @RequestBody final ChangeOrderLinesRequest request) {
    changeOrderLines.handle(request.toCommand(orderId));
    return ResponseEntity.noContent().build();
  }

  /**
   * 下書きの注文を確定する。
   *
   * <p>注文がなければ 404、画面が読んだ後に注文が変わっていれば 409、下書きでなければ 422 を返す。
   *
   * @param orderId 確定する注文の ID
   * @param request 画面が読んだ注文のロック番号
   * @return 本文のない 204 の応答
   */
  @Operation(operationId = "confirmOrder")
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PostMapping("/{orderId}/confirm")
  /* package */ ResponseEntity<Void> confirm(
      @PathVariable final UUID orderId, @Valid @RequestBody final ConfirmOrderRequest request) {
    confirmOrder.handle(request.toCommand(orderId));
    return ResponseEntity.noContent().build();
  }

  /**
   * 下書きの注文を取り消す。
   *
   * <p>注文がなければ 404、画面が読んだ後に注文が変わっていれば 409、下書きでなければ 422 を返す。
   *
   * @param orderId 取り消す注文の ID
   * @param request 画面が読んだ注文のロック番号
   * @return 本文のない 204 の応答
   */
  @Operation(operationId = "cancelOrder")
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PostMapping("/{orderId}/cancel")
  /* package */ ResponseEntity<Void> cancel(
      @PathVariable final UUID orderId, @Valid @RequestBody final CancelOrderRequest request) {
    cancelOrder.handle(request.toCommand(orderId));
    return ResponseEntity.noContent().build();
  }
}
