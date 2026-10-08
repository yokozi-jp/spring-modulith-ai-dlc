package com.example.demo.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.payment.domain.model.ChargeOutcome;
import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentRepository;
import com.example.demo.payment.domain.model.PaymentStatus;
import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import com.example.demo.testkit.DatabaseTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 決済記録の保存と読み戻しと、同じ注文の 2 件目の保存の拒否を検証する。 */
@DatabaseTest
class JooqPaymentRepositoryTest {

  /** 請求の結果を記録した時刻。マイクロ秒の桁まで往復することを確かめる。 */
  private static final Instant RECORDED_AT = Instant.parse("2026-10-06T01:02:03.123456Z");

  /** 請求した金額。 */
  private static final String AMOUNT = "240.00";

  /** テスト対象が使う jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("保存した決済記録を、注文 ID で金額、識別子、マイクロ秒の時刻、ロック番号ごと読み戻せる")
  void savesAndFindsByOrderId() {
    final OrderId orderId = new OrderId(UUID.randomUUID());
    final Payment payment = payment(orderId, AMOUNT);
    TestCommonColumns.runAs(() -> repository().add(payment));

    final Payment found =
        repository()
            .findByOrderId(orderId)
            .orElseThrow(() -> new AssertionError("決済記録がない: orderId=" + orderId.value()));

    assertThat(found.id()).isEqualTo(payment.id());
    assertThat(found.orderId()).isEqualTo(orderId);
    assertThat(found.amount().amount()).isEqualByComparingTo(new BigDecimal(AMOUNT));
    assertThat(found.status()).isEqualTo(PaymentStatus.PAID);
    assertThat(found.gatewayPaymentCode()).isEqualTo(payment.gatewayPaymentCode());
    assertThat(found.recordedAt()).isEqualTo(RECORDED_AT);
    assertThat(found.lockNo()).isEqualTo(1L);
    assertThat(repository().findByOrderId(new OrderId(UUID.randomUUID()))).isEmpty();
  }

  @Test
  @DisplayName("識別子のない契約の不備の失敗と、識別子付きの拒否を、結果ごと読み戻せる")
  void savesFailedAndDeclined() {
    final OrderId failedOrder = new OrderId(UUID.randomUUID());
    final OrderId declinedOrder = new OrderId(UUID.randomUUID());
    final GatewayPaymentCode declinedCode = new GatewayPaymentCode("ch_" + declinedOrder.value());
    final PaymentRepository repository = repository();
    TestCommonColumns.runAs(
        () -> repository.add(payment(failedOrder, AMOUNT, ChargeOutcome.failed())));
    TestCommonColumns.runAs(
        () -> repository.add(payment(declinedOrder, AMOUNT, ChargeOutcome.declined(declinedCode))));

    assertThat(repository.findByOrderId(failedOrder))
        .as("orderId=%s の契約の不備の決済記録", failedOrder.value())
        .hasValueSatisfying(
            found -> {
              assertThat(found.status()).isEqualTo(PaymentStatus.FAILED);
              assertThat(found.gatewayPaymentCode()).isNull();
            });
    assertThat(repository.findByOrderId(declinedOrder))
        .as("orderId=%s の拒否の決済記録", declinedOrder.value())
        .hasValueSatisfying(
            found -> {
              assertThat(found.status()).isEqualTo(PaymentStatus.DECLINED);
              assertThat(found.gatewayPaymentCode()).isEqualTo(declinedCode);
            });
  }

  @Test
  @DisplayName("同じ注文の 2 件目の保存は、種類が UNIQUE の ConflictException になる")
  void secondPaymentOfSameOrderBecomesConflict() {
    final OrderId orderId = new OrderId(UUID.randomUUID());
    final PaymentRepository repository = repository();
    TestCommonColumns.runAs(() -> repository.add(payment(orderId, AMOUNT)));
    final Payment duplicate = payment(orderId, AMOUNT);

    assertThatThrownBy(() -> TestCommonColumns.runAs(() -> repository.add(duplicate)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            conflict ->
                assertThat(conflict.kind())
                    .as("orderId=%s の 2 件目の保存の衝突の種類", orderId.value())
                    .isEqualTo(ConflictException.Kind.UNIQUE));
  }

  private PaymentRepository repository() {
    final CommonColumns commonColumns = TestCommonColumns.at(RECORDED_AT);
    return new JooqPaymentRepository(dsl, commonColumns, new TableWriter(dsl, commonColumns));
  }

  private static Payment payment(final OrderId orderId, final String amount) {
    return payment(
        orderId, amount, ChargeOutcome.paid(new GatewayPaymentCode("test-" + orderId.value())));
  }

  private static Payment payment(
      final OrderId orderId, final String amount, final ChargeOutcome outcome) {
    return Payment.record(orderId, new Money(new BigDecimal(amount)), outcome, RECORDED_AT);
  }
}
