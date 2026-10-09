package com.example.demo.payment.infrastructure.persistence;

import com.example.demo.payment.domain.model.ChargeOutcome;
import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentId;
import com.example.demo.payment.domain.model.PaymentStatus;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/** 開発用の代表データ（#114）の決済記録を入れる。 */
public final class PaymentSeeds {

  /** シーダーが書く {@code *_pgm_cd}。 */
  private static final String PGM_CD = "payment.SeedLocalData";

  private PaymentSeeds() {}

  /**
   * 請求を受け付けた、または決済代行が拒否した決済記録を入れる。
   *
   * <p>{@code gateway_payment_code} は WireMock のスタブ（{@code
   * docker/wiremock/mappings/payment-gateway-charge.json} と E2E の拒否のスタブ）が返す {@code chargeId} と同じ
   * {@code ch_<注文 ID>} にする。
   *
   * @param declined true なら決済代行が拒否した決済、false なら受け付けた決済にする
   */
  public static void insert(
      final DSLContext dsl,
      final UUID paymentId,
      final UUID orderId,
      final BigDecimal amount,
      final boolean declined,
      final Instant recordedAt) {
    final PaymentStatus status = declined ? PaymentStatus.DECLINED : PaymentStatus.PAID;
    final Payment payment =
        Payment.restore(
            new PaymentId(paymentId),
            new OrderId(orderId),
            new Money(amount),
            new ChargeOutcome(status, new GatewayPaymentCode("ch_" + orderId)),
            recordedAt,
            1L);
    final CommonColumns commonColumns = TestCommonColumns.at(recordedAt);
    TestCommonColumns.runAs(
        PGM_CD,
        () ->
            new JooqPaymentRepository(dsl, commonColumns, new TableWriter(dsl, commonColumns))
                .add(payment));
  }
}
