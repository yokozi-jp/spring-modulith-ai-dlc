package com.example.demo.payment.application;

import com.example.demo.order.OrderDetails;
import com.example.demo.order.OrderQueries;
import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.payment.domain.model.PaymentRepository;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.shared.failure.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 確定した注文の代金を、注文 ID を冪等キーにして請求し、決済記録を保存する。 */
@Service
public class ChargeOrderCommandHandler {

  /** 請求できる注文の状態。 */
  private static final String CONFIRMED = "CONFIRMED";

  /** 決済記録を取り出して保存する Repository。 */
  private final PaymentRepository paymentRepository;

  /** 外部の決済代行。 */
  private final PaymentGateway paymentGateway;

  /** 請求する金額を読む注文の参照。 */
  private final OrderQueries orderQueries;

  /** 決済した時刻を取る時計。 */
  private final Clock clock;

  /** 依存を受け取る。 */
  public ChargeOrderCommandHandler(
      final PaymentRepository paymentRepository,
      final PaymentGateway paymentGateway,
      final OrderQueries orderQueries,
      final Clock clock) {
    this.paymentRepository = paymentRepository;
    this.paymentGateway = paymentGateway;
    this.orderQueries = orderQueries;
    this.clock = clock;
  }

  /**
   * 注文の決済記録がなければ、確定した注文の合計を請求して決済記録を保存する。決済記録があれば請求せずに返す。
   *
   * @throws NotFoundException 注文がない場合
   * @throws BusinessRuleViolationException 注文が確定していない場合
   */
  @Transactional
  public ChargeOrderResult handle(final ChargeOrderCommand command) {
    final OrderId orderId = new OrderId(UUID.fromString(command.orderId()));
    // 二度目の請求を避けるための先読み。並行の配信は INSERT の一意制約が ConflictException にする。
    final Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
    if (existing.isPresent()) {
      return new ChargeOrderResult(existing.get().id().value().toString());
    }
    final OrderDetails details =
        orderQueries
            .findDetails(command.orderId())
            .orElseThrow(
                () -> new NotFoundException("order not found: orderId=" + command.orderId()));
    // 他モジュールの参照の結果に対する前提の確認であり、決済の業務規則ではないため、集約に置かない。
    if (!CONFIRMED.equals(details.status())) {
      throw new BusinessRuleViolationException(
          "order is not CONFIRMED: orderId=" + command.orderId() + ", status=" + details.status());
    }
    final Money amount = new Money(details.totalAmount());
    final GatewayPaymentCode code = paymentGateway.charge(orderId, amount);
    final Payment payment = Payment.record(orderId, amount, code, Instant.now(clock));
    paymentRepository.add(payment);
    return new ChargeOrderResult(payment.id().value().toString());
  }
}
