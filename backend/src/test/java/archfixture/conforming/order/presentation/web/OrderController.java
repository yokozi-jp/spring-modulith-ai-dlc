package archfixture.conforming.order.presentation.web;

import archfixture.conforming.order.OrderQueries;
import archfixture.conforming.order.application.PlaceOrderCommandHandler;
import archfixture.conforming.order.application.PlaceOrderResult;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 注文の HTTP API。 */
@RestController
@RequestMapping("/api/orders")
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
  /* package */ ResponseEntity<Void> place(@Valid @RequestBody final PlaceOrderRequest request) {
    final PlaceOrderResult result = placeOrder.handle(request.toCommand());
    return ResponseEntity.created(URI.create("/api/orders/" + result.orderId())).build();
  }

  /** 注文の詳細を返す。 */
  @GetMapping("/{orderId}")
  /* package */ OrderDetailsResponse details(@PathVariable final String orderId) {
    return orderQueries
        .findDetails(orderId)
        .map(OrderDetailsResponse::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
