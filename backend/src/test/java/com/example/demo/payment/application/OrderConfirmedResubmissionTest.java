package com.example.demo.payment.application;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;

/**
 * 失敗した {@link OrderConfirmed} の出版を、テナントを束縛していないスレッドから再投入したときのテナントの扱いを検証する。
 *
 * <p>定期の再投入はスケジューラのスレッドで動き、テナントを束縛しない。同じ状態にするため、このクラスには {@code BindTestTenantExtension}
 * を付けず、テストの利用者のテナントでもない別のテナントの注文を使う。構成は {@link OrderConfirmedListenerTest} と同じにし、Spring
 * のコンテキストを共有する。
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@Import({
  SharedTestConfiguration.class,
  OrderConfirmedFixture.class,
  OrderConfirmedListenerTest.GatewayConfiguration.class
})
@ExtendWith(CleanGeneratedTablesExtension.class)
class OrderConfirmedResubmissionTest {

  /** テストの利用者のテナント（{@link AsTenant#TENANT}）でない、注文と決済のテナント。 */
  private static final TenantId TENANT_B =
      new TenantId(UUID.fromString("0b0b0b0b-0000-4000-8000-00000000000b"));

  /** 確定する注文を作り、出版の状態を読む補助。 */
  @Autowired private OrderConfirmedFixture fixture;

  /** 切り替えられる決済代行。 */
  @Autowired private OrderConfirmedListenerTest.ControllablePaymentGateway gateway;

  /** 失敗した出版の再投入。 */
  @Autowired private FailedEventPublications failedEventPublications;

  /** 出版の時刻を戻し、決済記録を読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 固定した時計。出版の時刻になる。 */
  @Autowired private Clock clock;

  @BeforeEach
  void resetGateway() {
    gateway.reset();
  }

  @Test
  @DisplayName("別のテナントの失敗した出版を、テナントを束縛していないスレッドから再投入すると、イベントのテナントで請求して決済記録を作る")
  void failedPublicationIsResubmittedInItsEventTenant(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId(TENANT_B);
    gateway.setFailing(true);
    fixture.confirmAndAwait(
        scenario, TENANT_B, orderId, "FAILED の出版", state -> "FAILED".equals(state.status()));
    gateway.setFailing(false);

    assertThat(AsTenant.currentOrUnbound()).as("再投入するスレッドのテナントの束縛").isEqualTo("unbound");
    resubmit(orderId);
    OrderConfirmedFixture.await(
        scenario.stimulate(() -> {}),
        orderId,
        "再投入の完了した出版",
        () -> fixture.registry(orderId),
        state -> state.archived() == 1);

    assertThat(gateway.recordedTenants())
        .as("orderId=%s の失敗した請求と再投入の請求の時点のテナントの束縛", orderId)
        .containsExactly(TENANT_B.toString(), TENANT_B.toString());
    assertThat(gateway.recordedTenantSettings())
        .as("orderId=%s の失敗した請求と再投入の請求の時点の、Listener のトランザクションのテナントの設定", orderId)
        .containsExactly(TENANT_B.toString(), TENANT_B.toString());
    final int payments =
        AsModule.call(
            dsl,
            "payment",
            TENANT_B,
            tx -> tx.fetchCount(T_PAYMENT, T_PAYMENT.ORDER_PUBLIC_ID.eq(UUID.fromString(orderId))));
    assertThat(payments).as("orderId=%s の、イベントのテナントの決済記録", orderId).isEqualTo(1);
  }

  /** 注文 ID の出版を、再投入できるよう時計の 1 マイクロ秒前の出版にして、再投入する。 */
  private void resubmit(final String orderId) {
    // 時計は止まっているため、出版の時刻が今のままでは再投入の対象にならない（ResubmissionOptionsLearningTest の
    // defaultsResubmitOnlyPublicationsOlderThanNow）。
    dsl.update(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.PUBLICATION_DATE, Instant.now(clock).minus(1, ChronoUnit.MICROS))
        .where(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(orderId))
        .execute();
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withFilter(publication ->
                publication.getEvent() instanceof OrderConfirmed event
                    && event.orderId().equals(orderId)));
  }
}
