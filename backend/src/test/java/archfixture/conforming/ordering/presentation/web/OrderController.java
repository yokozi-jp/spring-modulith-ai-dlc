package archfixture.conforming.ordering.presentation.web;

import archfixture.conforming.ordering.OrderQueries;
import archfixture.conforming.ordering.OrderSearchCriteria;
import archfixture.conforming.ordering.OrderSummary;
import archfixture.conforming.ordering.application.PlaceOrderCommandHandler;
import archfixture.conforming.ordering.application.PlaceOrderResult;
import archfixture.conforming.shared.failure.NotFoundException;
import archfixture.conforming.shared.idempotency.IdempotencyKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 注文の HTTP API。 */
@RestController
@RequestMapping(path = "/api/v{version}/orders", version = "1")
class OrderController {

  /** 注文を受け付ける CommandHandler。 */
  private final PlaceOrderCommandHandler placeOrder;

  /** 注文の読み取り窓口。 */
  private final OrderQueries orderQueries;

  /** 依存を受け取る。 */
  /* package */ OrderController(
      final PlaceOrderCommandHandler placeOrder, final OrderQueries orderQueries) {
    this.placeOrder = placeOrder;
    this.orderQueries = orderQueries;
  }

  /** 注文を受け付け、作成した注文の URI を返す。 */
  @PostMapping
  /* package */ ResponseEntity<Void> place(
      final IdempotencyKey idempotencyKey, @Valid @RequestBody final PlaceOrderRequest request) {
    final PlaceOrderResult result = placeOrder.handle(request.toCommand(idempotencyKey));
    return ResponseEntity.created(URI.create("/api/v1/orders/" + result.orderId())).build();
  }

  /** キーを求めない印を付けた操作。Idempotency-Key の規則の対象にならない。 */
  @Operation(
      extensions =
          @Extension(properties = @ExtensionProperty(name = "x-idempotency-key", value = "none")))
  @PostMapping("/selection")
  /* package */ ResponseEntity<Void> select() {
    return ResponseEntity.noContent().build();
  }

  /** 注文の詳細を返す。 */
  @GetMapping("/{orderId}")
  /* package */ OrderDetailsResponse details(@PathVariable final String orderId) {
    return orderQueries
        .findDetails(orderId)
        .map(OrderDetailsResponse::from)
        .orElseThrow(() -> new NotFoundException("order not found: orderId=" + orderId));
  }

  /** 顧客の注文の状態の名前を返す。状態を持つモジュールの中なので、状態の名前を読んでよい。 */
  @GetMapping
  /* package */ List<String> statuses(@RequestParam final String customerId) {
    return orderQueries.search(new OrderSearchCriteria(customerId)).stream()
        .map(OrderSummary::status)
        .toList();
  }
}
