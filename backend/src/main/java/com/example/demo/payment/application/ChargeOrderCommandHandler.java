package com.example.demo.payment.application;

import com.example.demo.ordering.OrderDetails;
import com.example.demo.ordering.OrderQueries;
import com.example.demo.payment.domain.model.ChargeOutcome;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.payment.domain.model.PaymentRepository;
import com.example.demo.shared.failure.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 確定した注文の代金を、注文 ID を冪等キーにして請求し、請求の結果を決済記録に保存する。
 *
 * <p>決済代行の拒否と契約の不備も決済記録に残して正常に返し、イベント出版を COMPLETED にする。一時障害と資格情報の不備は決済代行の例外がそのまま伝わり、出版は FAILED
 * に残る（ADR-072）。
 *
 * <p>リスナーが動く前に取り消された注文のように、確定していない注文は請求せず、決済記録を作らずに正常に返す。業務の結果であり、再投入で変わらないためである。
 */
@Slf4j
@Service
public class ChargeOrderCommandHandler {

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
   * 注文の決済記録がなければ、確定した注文の合計を請求して結果を決済記録に保存する。決済記録があれば請求せずに返す。注文が確定していなければ請求せずに返す。
   *
   * @throws NotFoundException
   *     注文がない場合。確定のイベントがあるのに注文がないのは内部の不変条件の違反（不具合）であり、業務の状態に記録すると隠れるため投げ、イベント出版を FAILED
   *     のデッドレターに残す（ADR-072）
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
    if (!details.confirmed()) {
      final String orderIdValue = command.orderId();
      final String orderStatus = details.status();
      log.info(
          "Skipped charging an order that is not confirmed: orderId={}, status={}",
          orderIdValue,
          orderStatus);
      return new ChargeOrderResult(null);
    }
    final Money amount = new Money(details.totalAmount());
    final ChargeOutcome outcome = paymentGateway.charge(orderId, amount);
    final Payment payment = Payment.record(orderId, amount, outcome, Instant.now(clock));
    paymentRepository.add(payment);
    return new ChargeOrderResult(payment.id().value().toString());
  }
}
