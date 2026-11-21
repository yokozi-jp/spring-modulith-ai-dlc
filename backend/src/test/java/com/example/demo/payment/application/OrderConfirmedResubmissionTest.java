package com.example.demo.payment.application;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.shared.tenant.TenantId;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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

  /** 失敗した出版の ERROR を記録する Spring の処理。 */
  private static final String ASYNC_ERROR_SCOPE =
      "org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler";

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

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

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
        scenario, TENANT_B, orderId, "FAILED の出版", OrderConfirmedResubmissionTest::failed);
    gateway.setFailing(false);

    assertThat(AsTenant.currentOrUnbound()).as("再投入するスレッドのテナントの束縛").isEqualTo("unbound");
    backdate(orderId, 1);
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

  @Test
  @DisplayName("tenantId を持たない古い出版は、再投入しても ERROR を記録して FAILED に残り、同じ回に後から再投入した出版の完了を妨げない")
  void publicationWithoutTenantStaysFailedWithoutBlockingOthers(final Scenario scenario) {
    final String oldOrderId = fixture.draftedOrderId(TENANT_B);
    final String orderId = fixture.draftedOrderId(TENANT_B);
    gateway.setFailing(true);
    fixture.confirmAndAwait(
        scenario, TENANT_B, oldOrderId, "FAILED の出版", OrderConfirmedResubmissionTest::failed);
    fixture.confirmAndAwait(
        scenario, TENANT_B, orderId, "FAILED の出版", OrderConfirmedResubmissionTest::failed);
    gateway.setFailing(false);
    // イベントがテナントを運ぶ前に書いた出版の形にする。
    dsl.update(EVENT_PUBLICATION)
        .set(
            EVENT_PUBLICATION.SERIALIZED_EVENT,
            DSL.field(
                "({0}::jsonb - 'tenantId')::text",
                String.class, EVENT_PUBLICATION.SERIALIZED_EVENT))
        .where(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(oldOrderId))
        .execute();

    // 古い順に再投入するため、テナントのない出版を先にする。
    backdate(oldOrderId, 2);
    backdate(orderId, 1);
    resubmit(oldOrderId, orderId);
    OrderConfirmedFixture.await(
        scenario.stimulate(() -> {}),
        orderId,
        "再投入の完了した出版",
        () -> fixture.registry(orderId),
        state -> state.archived() == 1);
    OrderConfirmedFixture.await(
        scenario.stimulate(() -> {}),
        oldOrderId,
        "attempts 2 の FAILED の出版",
        () -> fixture.registry(oldOrderId),
        state -> failed(state) && Integer.valueOf(2).equals(state.attempts()));

    assertThat(capturedLogRecords.withScope(ASYNC_ERROR_SCOPE))
        .as("orderId=%s のテナントのない出版の ERROR のログ", oldOrderId)
        .anySatisfy(log -> {
          assertThat(log.getSeverity()).isEqualTo(Severity.ERROR);
          assertThat(log.getAttributes().get(AttributeKey.stringKey("exception.type")))
              .isEqualTo(IllegalStateException.class.getName());
          assertThat(log.getAttributes().get(AttributeKey.stringKey("exception.message")))
              .contains(OrderConfirmed.class.getName());
        });
  }

  private static boolean failed(final OrderConfirmedFixture.RegistryState state) {
    return "FAILED".equals(state.status());
  }

  /** 時計は止まっているため、注文 ID の出版の時刻を時計より前にして、再投入の対象にする。 */
  private void backdate(final String orderId, final long micros) {
    // ResubmissionOptions の既定は、時計の今より前の出版だけを再投入する（ResubmissionOptionsLearningTest の
    // defaultsResubmitOnlyPublicationsOlderThanNow）。
    dsl.update(EVENT_PUBLICATION)
        .set(
            EVENT_PUBLICATION.PUBLICATION_DATE, Instant.now(clock).minus(micros, ChronoUnit.MICROS))
        .where(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(orderId))
        .execute();
  }

  /** 注文 ID の失敗した出版を、1 回の再投入でまとめて再投入する。 */
  private void resubmit(final String... orderIds) {
    final List<String> targets = List.of(orderIds);
    failedEventPublications.resubmit(
        ResubmissionOptions.defaults()
            .withFilter(publication ->
                publication.getEvent() instanceof OrderConfirmed event
                    && targets.contains(event.orderId())));
  }
}
