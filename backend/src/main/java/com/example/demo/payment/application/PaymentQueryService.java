package com.example.demo.payment.application;

import com.example.demo.payment.PaymentQueries;
import com.example.demo.payment.PaymentSearchCriteria;
import com.example.demo.payment.PaymentSummary;
import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 決済記録の参照を、Repository で読んだ集約から作る。 */
@Service
class PaymentQueryService implements PaymentQueries {

  /** 決済記録を取り出す Repository。 */
  private final PaymentRepository paymentRepository;

  /** 決済記録を取り出す Repository を受け取る。 */
  /* package */ PaymentQueryService(final PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<PaymentSummary> search(final PaymentSearchCriteria criteria) {
    return paymentRepository
        .findByOrderId(new OrderId(UUID.fromString(criteria.orderId())))
        .map(PaymentQueryService::toSummary)
        .stream()
        .toList();
  }

  private static PaymentSummary toSummary(final Payment payment) {
    final GatewayPaymentCode code = payment.gatewayPaymentCode();
    return new PaymentSummary(
        payment.id().value().toString(),
        payment.orderId().value().toString(),
        payment.amount().amount(),
        payment.status().name(),
        code == null ? null : code.value(),
        payment.recordedAt());
  }
}
