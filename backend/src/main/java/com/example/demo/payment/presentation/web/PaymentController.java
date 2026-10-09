package com.example.demo.payment.presentation.web;

import com.example.demo.payment.PaymentQueries;
import com.example.demo.payment.PaymentSearchCriteria;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 決済記録の HTTP API。 */
@RestController
@RequestMapping("/api/payments")
@Tag(name = "payment", description = "決済の API")
class PaymentController {

  /** 決済記録の参照。 */
  private final PaymentQueries paymentQueries;

  /** 決済記録の参照を受け取る。 */
  /* package */ PaymentController(final PaymentQueries paymentQueries) {
    this.paymentQueries = paymentQueries;
  }

  /**
   * 注文の決済記録を返す。
   *
   * <p>決済記録は 1 注文につき 1 件までなので、items に 0 件か 1 件を入れて返す。決済記録がない注文と、存在しない注文では、空の items を返す。
   *
   * @param orderId 決済した注文の ID
   * @return 決済記録の一覧
   */
  @Operation(operationId = "listPayments")
  @GetMapping
  /* package */ PaymentSummaryListResponse search(@RequestParam final UUID orderId) {
    return new PaymentSummaryListResponse(
        paymentQueries.search(new PaymentSearchCriteria(orderId.toString())).stream()
            .map(PaymentSummaryResponse::from)
            .toList());
  }
}
