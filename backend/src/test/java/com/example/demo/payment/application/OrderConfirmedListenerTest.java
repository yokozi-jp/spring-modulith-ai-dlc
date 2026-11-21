package com.example.demo.payment.application;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.payment.Tables.T_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.ordering.application.CancelOrderCommand;
import com.example.demo.ordering.application.CancelOrderCommandHandler;
import com.example.demo.payment.PaymentQueries;
import com.example.demo.payment.PaymentSearchCriteria;
import com.example.demo.payment.PaymentSummary;
import com.example.demo.payment.domain.model.ChargeOutcome;
import com.example.demo.payment.domain.model.GatewayPaymentCode;
import com.example.demo.payment.domain.model.Money;
import com.example.demo.payment.domain.model.OrderId;
import com.example.demo.payment.domain.model.PaymentGateway;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.infrastructure.persistence.ModuleRoleTransactionManager;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.BindTestTenantExtension;
import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Severity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.modulith.test.Scenario;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.client.ResourceAccessException;

/**
 * 注文の確定を受けて決済記録を作ること、二度目の配信で二重に請求しないこと、失敗した出版を再投入で完了できることを検証する。
 *
 * <p>{@code payment} の直接の依存は {@code order} だけだが、{@code order} の CommandHandler が {@code product}
 * の参照を要るため、すべての依存を起動する。決済代行の port はこのテストの中で結果を切り替えられる実装に、時計は固定の時刻に差し替える。HTTP の Client と WireMock
 * の結合は {@link PaymentGatewayClientIntegrationTest} で検証する。Listener の処理はコミットされるため、各テストの後に全テーブルを消す。
 */
// 確定から再投入までのイベント出版の状態を一つの文脈で確かめるため、型とメソッドの数が多い。
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@Import({
  SharedTestConfiguration.class,
  OrderConfirmedFixture.class,
  OrderConfirmedListenerTest.GatewayConfiguration.class
})
@ExtendWith({CleanGeneratedTablesExtension.class, BindTestTenantExtension.class})
class OrderConfirmedListenerTest {

  /** 固定した現在時刻。決済した時刻と、イベント出版の時刻になる。 */
  private static final Instant NOW = Instant.parse("2026-10-06T01:02:03.123456Z");

  /** 決済の CommandHandler の {@code *_pgm_cd}。Listener の中の書き込みの {@code *_by} も同じ値になる。 */
  private static final String PGM_CD = "payment.ChargeOrder";

  /** 失敗した出版の ERROR を記録する Spring の処理。 */
  private static final String ASYNC_ERROR_SCOPE =
      "org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler";

  /** 確定していない注文の請求を見送ったことを記録する CommandHandler。 */
  private static final String HANDLER_SCOPE = ChargeOrderCommandHandler.class.getName();

  /** 決済の参照。 */
  @Autowired private PaymentQueries paymentQueries;

  /** 確定する注文を作り、出版の状態を読む補助。 */
  @Autowired private OrderConfirmedFixture fixture;

  /** 注文を取り消す CommandHandler。 */
  @Autowired private CancelOrderCommandHandler cancelOrder;

  /** 切り替えられる決済代行。 */
  @Autowired private ControllablePaymentGateway gateway;

  /** 失敗した出版の再投入。 */
  @Autowired private FailedEventPublications failedEventPublications;

  /** 決済記録を読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 確定の observation の trace ID を読む。 */
  @Autowired private Tracer tracer;

  /** OTLP へ送る LogRecord。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  @BeforeEach
  void resetGateway() {
    gateway.reset();
  }

  @Test
  @DisplayName("確定した注文の合計を注文 ID で 1 回請求し、確定の trace と処理の名前で決済記録を作り、出版を完了にする")
  void chargesConfirmedOrder(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    final AtomicReference<String> confirmTraceId = new AtomicReference<>();

    runAsUser(() ->
        fixture.observed(() -> {
          confirmTraceId.set(currentTraceId());
          OrderConfirmedFixture.await(
              scenario.stimulate(() -> fixture.confirm(orderId)),
              orderId,
              "完了した出版",
              () -> fixture.registry(orderId),
              state -> state.archived() == 1);
          return null;
        }));

    final List<PaymentSummary> payments = payments(orderId);
    assertThat(payments).as("orderId=%s の決済記録", orderId).hasSize(1);
    assertThat(payments.getFirst().orderId()).isEqualTo(orderId);
    assertThat(payments.getFirst().amount()).isEqualByComparingTo(new BigDecimal("240.00"));
    assertThat(payments.getFirst().status()).isEqualTo("PAID");
    assertThat(payments.getFirst().recordedAt()).isEqualTo(NOW);
    assertThat(gateway.chargedOrders())
        .as("orderId=%s の請求", orderId)
        .containsExactly(orderId(orderId));
    assertThat(gateway.recordedTraceIds())
        .as("orderId=%s の請求の trace ID は確定の trace ID と同じ", orderId)
        .containsExactly(confirmTraceId.get());
    assertThat(createdByAndPgmCd(orderId))
        .as("orderId=%s の決済記録の created_by は、確定した利用者でなく pgm_cd", orderId)
        .isEqualTo(List.of(PGM_CD, PGM_CD));
  }

  @Test
  @DisplayName("Listener が呼ぶ決済代行の時点で、イベントのテナントが束縛され、Listener のトランザクションに設定されている")
  void gatewayIsChargedInEventTenant(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    fixture.confirmAndAwait(scenario, orderId, "完了した出版", state -> state.archived() == 1);

    assertThat(gateway.recordedTenants())
        .as("orderId=%s の請求の時点のテナントの束縛", orderId)
        .containsExactly(AsTenant.TENANT.toString());
    assertThat(gateway.recordedTenantSettings())
        .as("orderId=%s の請求の時点の、Listener のトランザクションのテナントの設定", orderId)
        .containsExactly(AsTenant.TENANT.toString());
  }

  @Test
  @DisplayName("同じ注文の OrderConfirmed を二度受けても、決済記録は 1 件のままで二度目は請求しない")
  void secondDeliveryDoesNotChargeAgain(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    fixture.confirmAndAwait(scenario, orderId, "1 件目の完了した出版", state -> state.archived() == 1);

    OrderConfirmedFixture.await(
        scenario.publish(new OrderConfirmed(orderId, NOW, AsTenant.TENANT)),
        orderId,
        "2 件目の完了した出版",
        () -> fixture.registry(orderId),
        state -> state.archived() == 2);

    assertThat(payments(orderId)).as("orderId=%s の決済記録", orderId).hasSize(1);
    assertThat(gateway.chargedOrders()).as("orderId=%s の請求", orderId).hasSize(1);
  }

  @Test
  @DisplayName("決済代行が失敗すると出版は FAILED で残り、再投入すると PROCESSING を経て attempts 2 で完了する")
  void failedChargeIsResubmittedAndCompleted(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    gateway.setFailing(true);

    // 1. 失敗：出版は FAILED、attempts 1、決済記録はなく、Spring が ERROR を記録する。
    runAsUser(() ->
        fixture.confirmAndAwait(
            scenario, orderId, "FAILED の出版", state -> "FAILED".equals(state.status())));
    final OrderConfirmedFixture.RegistryState failed = fixture.registry(orderId);
    assertThat(failed.attempts()).as("orderId=%s の FAILED の attempts", orderId).isEqualTo(1);
    assertThat(failed.completionDate()).as("orderId=%s の completion_date", orderId).isNull();
    assertThat(payments(orderId)).as("orderId=%s の失敗後の決済記録", orderId).isEmpty();
    assertThat(capturedLogRecords.withScope(ASYNC_ERROR_SCOPE))
        .as("orderId=%s の失敗の ERROR のログ", orderId)
        .anySatisfy(log -> {
          assertThat(log.getSeverity()).isEqualTo(Severity.ERROR);
          assertThat(log.getAttributes().get(AttributeKey.stringKey("exception.message")))
              .contains("orderId=" + orderId);
        });

    // 2. 決済代行を成功に戻し、請求の手前で止める。
    gateway.setFailing(false);
    gateway.hold();
    try {
      // 3. 認証も observation もないテストのスレッドから再投入する。
      // 時計は NOW で止まっているため、再投入できるよう出版の時刻を 1 マイクロ秒前にする（ResubmissionOptionsLearningTest の
      // defaultsResubmitOnlyPublicationsOlderThanNow）。
      dsl.update(EVENT_PUBLICATION)
          .set(EVENT_PUBLICATION.PUBLICATION_DATE, NOW.minus(1, ChronoUnit.MICROS))
          .where(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(orderId))
          .execute();
      failedEventPublications.resubmit(
          ResubmissionOptions.defaults()
              .withFilter(publication ->
                  publication.getEvent() instanceof OrderConfirmed event
                      && event.orderId().equals(orderId)));

      // 4. 請求の手前で止まっている間、出版は PROCESSING、attempts 2。
      OrderConfirmedFixture.await(
          scenario.stimulate(() -> {}),
          orderId,
          "再投入の請求の呼び出し",
          () -> fixture.registry(orderId),
          state -> gateway.waitingCalls() > 0);
      final OrderConfirmedFixture.RegistryState processing = fixture.registry(orderId);
      assertThat(processing.status()).as("orderId=%s の再投入中の状態", orderId).isEqualTo("PROCESSING");
      assertThat(processing.attempts()).as("orderId=%s の再投入中の attempts", orderId).isEqualTo(2);
      assertThat(processing.lastResubmissionDate())
          .as("orderId=%s の last_resubmission_date", orderId)
          .isNotNull();
    } finally {
      // 5. 請求を進め、完了を待つ。
      gateway.release();
    }
    OrderConfirmedFixture.await(
        scenario.stimulate(() -> {}),
        orderId,
        "再投入の完了した出版",
        () -> fixture.registry(orderId),
        state -> state.archived() == 1);

    final OrderConfirmedFixture.RegistryState completed = fixture.registry(orderId);
    assertThat(completed.status()).as("orderId=%s の再投入の後の状態", orderId).isEqualTo("COMPLETED");
    assertThat(completed.attempts()).as("orderId=%s の再投入の後の attempts", orderId).isEqualTo(2);
    assertThat(completed.lastResubmissionDate()).isNotNull();
    assertThat(completed.incomplete()).as("orderId=%s の未完了の出版", orderId).isZero();
    assertThat(payments(orderId)).as("orderId=%s の再投入の後の決済記録", orderId).hasSize(1);
    assertThat(gateway.chargedOrders()).as("orderId=%s の成功した請求", orderId).hasSize(1);
    assertThat(createdByAndPgmCd(orderId))
        .as("orderId=%s の再投入で作った決済記録の created_by は pgm_cd", orderId)
        .isEqualTo(List.of(PGM_CD, PGM_CD));
  }

  @Test
  @DisplayName("取り消した注文の OrderConfirmed では請求せず、決済記録を作らず、INFO を記録して出版を完了にする")
  void cancelledOrderIsNotCharged(final Scenario scenario) {
    final String orderId = fixture.draftedOrderId();
    fixture.observed(
        () -> cancelOrder.handle(new CancelOrderCommand(orderId, new ExpectedLockNo(1))));

    OrderConfirmedFixture.await(
        scenario.publish(new OrderConfirmed(orderId, NOW, AsTenant.TENANT)),
        orderId,
        "完了した出版",
        () -> fixture.registry(orderId),
        state -> state.archived() == 1);

    assertThat(gateway.recordedTraceIds()).as("orderId=%s の取消後の決済代行の呼び出し", orderId).isEmpty();
    assertThat(payments(orderId)).as("orderId=%s の取消後の決済記録", orderId).isEmpty();
    assertThat(capturedLogRecords.withScope(HANDLER_SCOPE))
        .as("orderId=%s の確定していない注文の INFO のログ", orderId)
        .anySatisfy(log -> {
          assertThat(log.getSeverity()).isEqualTo(Severity.INFO);
          assertThat(log.getBodyValue()).isNotNull();
          assertThat(log.getBodyValue().asString())
              .contains("orderId=" + orderId + ", status=CANCELLED");
        });
    assertThat(capturedLogRecords.withScope(ASYNC_ERROR_SCOPE))
        .as("orderId=%s の確定していない注文の ERROR のログ", orderId)
        .noneSatisfy(log ->
            assertThat(log.getAttributes().get(AttributeKey.stringKey("exception.message")))
                .contains("orderId=" + orderId));
  }

  @Test
  @DisplayName("存在しない注文の OrderConfirmed では請求せず、決済記録を作らず、ERROR を記録して出版をデッドレターの FAILED に残す")
  void missingOrderLeavesPublicationIncomplete(final Scenario scenario) {
    final String orderId = UUID.randomUUID().toString();

    OrderConfirmedFixture.await(
        scenario.publish(new OrderConfirmed(orderId, NOW, AsTenant.TENANT)),
        orderId,
        "FAILED の出版",
        () -> fixture.registry(orderId),
        state -> "FAILED".equals(state.status()));

    assertThat(gateway.chargedOrders()).as("orderId=%s の請求", orderId).isEmpty();
    assertThat(payments(orderId)).as("orderId=%s の決済記録", orderId).isEmpty();
    assertThat(fixture.registry(orderId).incomplete())
        .as("orderId=%s の未完了の出版", orderId)
        .isEqualTo(1);
    assertThat(capturedLogRecords.withScope(ASYNC_ERROR_SCOPE))
        .as("orderId=%s の存在しない注文の ERROR のログ", orderId)
        .anySatisfy(log -> {
          assertThat(log.getSeverity()).isEqualTo(Severity.ERROR);
          assertThat(log.getAttributes().get(AttributeKey.stringKey("exception.message")))
              .contains("order not found: orderId=" + orderId);
        });
  }

  private List<PaymentSummary> payments(final String orderId) {
    return paymentQueries.search(new PaymentSearchCriteria(orderId));
  }

  private String currentTraceId() {
    final Span span = Objects.requireNonNull(tracer.currentSpan(), "確定の observation に span があること");
    return span.context().traceId();
  }

  /** 確定した利用者の認証を置いて処理を呼び、最後に外す。 */
  private static void runAsUser(final Runnable action) {
    final OidcUser user = AppUsers.oidcUser();
    SecurityContextHolder.getContext()
        .setAuthentication(new OAuth2AuthenticationToken(user, user.getAuthorities(), "web"));
    try {
      action.run();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /** 決済記録の created_by と created_pgm_cd を読む。 */
  private List<String> createdByAndPgmCd(final String orderId) {
    return AsModule.call(
        dsl,
        "payment",
        tx ->
            tx.select(T_PAYMENT.CREATED_BY, T_PAYMENT.CREATED_PGM_CD)
                .from(T_PAYMENT)
                .where(T_PAYMENT.ORDER_PUBLIC_ID.eq(UUID.fromString(orderId)))
                .fetchSingle(row -> List.of(row.value1(), row.value2())));
  }

  private static OrderId orderId(final String orderId) {
    return new OrderId(UUID.fromString(orderId));
  }

  /** 失敗の切り替え、呼び出しの記録、請求の手前で止める門を持つ決済代行。 */
  /* package */ static final class ControllablePaymentGateway implements PaymentGateway {

    /** 門が開くまで待つ上限の秒数。テストが止まったままにならないようにする。 */
    private static final long HOLD_TIMEOUT_SECONDS = 10;

    /** 請求の span を読む。 */
    private final Tracer tracer;

    /** 失敗の設定。 */
    private final AtomicBoolean failing = new AtomicBoolean();

    /** 成功した請求の注文 ID。 */
    private final List<OrderId> charged = new CopyOnWriteArrayList<>();

    /** 請求の中の trace ID。span がなければ空文字。 */
    private final List<String> traceIds = new CopyOnWriteArrayList<>();

    /** 請求の中で束縛されていたテナント。束縛されていなければ {@code unbound}。 */
    private final List<String> tenants = new CopyOnWriteArrayList<>();

    /** 請求の中で、呼び出し元のトランザクションに設定されていたテナント。一度も設定していない接続では空文字。 */
    private final List<String> tenantSettings = new CopyOnWriteArrayList<>();

    /** 請求の呼び出し元のトランザクションの設定を読む。 */
    private final DSLContext dsl;

    /** 請求の手前で止める門。開いていれば null。 */
    private final AtomicReference<@Nullable CountDownLatch> gate = new AtomicReference<>();

    /** 門の前で待っている呼び出しの数。 */
    private final AtomicInteger waiting = new AtomicInteger();

    /* package */ ControllablePaymentGateway(final Tracer tracer, final DSLContext dsl) {
      this.tracer = tracer;
      this.dsl = dsl;
    }

    @Override
    // 門で待つ間に割り込まれたら、割り込みの状態を戻して失敗させる。
    @SuppressWarnings("PMD.DoNotUseThreads")
    public ChargeOutcome charge(final OrderId orderId, final Money amount) {
      final @Nullable Span span = tracer.currentSpan();
      traceIds.add(span == null ? "" : span.context().traceId());
      tenants.add(AsTenant.currentOrUnbound());
      tenantSettings.add(
          dsl.fetchValue(
              DSL.field(
                  "coalesce(current_setting({0}, true), '')",
                  String.class, DSL.val(ModuleRoleTransactionManager.TENANT_SETTING))));
      final @Nullable CountDownLatch latch = gate.get();
      if (latch != null) {
        waiting.incrementAndGet();
        try {
          if (!latch.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("gate was not released: orderId=" + orderId.value());
          }
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted: orderId=" + orderId.value(), exception);
        }
      }
      if (failing.get()) {
        throw new ResourceAccessException(
            "test gateway is configured to fail: orderId=" + orderId.value());
      }
      charged.add(orderId);
      return ChargeOutcome.paid(new GatewayPaymentCode("test-" + orderId.value()));
    }

    /* package */ void reset() {
      failing.set(false);
      charged.clear();
      traceIds.clear();
      tenants.clear();
      tenantSettings.clear();
      gate.set(null);
      waiting.set(0);
    }

    /* package */ void setFailing(final boolean value) {
      failing.set(value);
    }

    /* package */ void hold() {
      gate.set(new CountDownLatch(1));
    }

    /* package */ void release() {
      final @Nullable CountDownLatch latch = gate.getAndSet(null);
      if (latch != null) {
        latch.countDown();
      }
    }

    /* package */ int waitingCalls() {
      return waiting.get();
    }

    /* package */ List<OrderId> chargedOrders() {
      return new ArrayList<>(charged);
    }

    /* package */ List<String> recordedTraceIds() {
      return new ArrayList<>(traceIds);
    }

    /* package */ List<String> recordedTenants() {
      return new ArrayList<>(tenants);
    }

    /* package */ List<String> recordedTenantSettings() {
      return new ArrayList<>(tenantSettings);
    }
  }

  /** 決済代行と時計を差し替える。 */
  @TestConfiguration(proxyBeanMethods = false)
  /* package */ static class GatewayConfiguration {

    @Bean
    @Primary
    /* package */ ControllablePaymentGateway controllablePaymentGateway(
        final Tracer tracer, final DSLContext dsl) {
      return new ControllablePaymentGateway(tracer, dsl);
    }

    @Bean
    @Primary
    /* package */ Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
