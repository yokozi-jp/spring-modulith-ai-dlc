package com.example.demo.payment.infrastructure.persistence;

import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.Payment;
import com.example.demo.payment.domain.model.PaymentId;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;

/** 開発用の代表データ（#114）の決済記録を 1 件入れる。 */
public final class PaymentSeeds {

  /** シーダーが書く {@code *_pgm_cd}。 */
  private static final String PGM_CD = "payment.SeedLocalData";

  private PaymentSeeds() {}

  /** 決済記録を入れる。{@code gateway_payment_code} は偽物の決済代行と同じ {@code fake-<注文 ID>} にする。 */
  public static void insert(
      final DSLContext dsl,
      final UUID paymentId,
      final UUID orderId,
      final BigDecimal amount,
      final Instant paidAt) {
    final Payment payment =
        Payment.restore(
            new PaymentId(paymentId),
            new OrderId(orderId),
            new Money(amount),
            new GatewayPaymentCode("fake-" + orderId),
            paidAt,
            1L);
    final CommonColumns commonColumns = TestCommonColumns.at(paidAt);
    TestCommonColumns.runAs(
        PGM_CD,
        () ->
            new JooqPaymentRepository(dsl, commonColumns, new TableWriter(dsl, commonColumns))
                .add(payment));
  }
}
