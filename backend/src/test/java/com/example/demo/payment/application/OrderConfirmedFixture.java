package com.example.demo.payment.application;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION_ARCHIVE;

import com.example.demo.order.OrderConfirmed;
import com.example.demo.order.application.ConfirmOrderCommand;
import com.example.demo.order.application.ConfirmOrderCommandHandler;
import com.example.demo.order.application.DraftOrderCommand;
import com.example.demo.order.application.DraftOrderCommandHandler;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.testkit.UniqueCodes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.awaitility.core.ConditionTimeoutException;
import org.jooq.DSLContext;
import org.jooq.Records;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.test.Scenario;

/**
 * {@code payment} の Listener の結合テストが、確定する注文を作り、注文 ID の {@link OrderConfirmed} の出版の状態を待って読む補助。
 *
 * <p>テストの {@code @Import} で Bean として登録する。
 */
final class OrderConfirmedFixture {

  /** イベント出版レジストリの {@code event_type}。 */
  private static final String EVENT_TYPE = OrderConfirmed.class.getName();

  /** 商品の行を登録し、レジストリを読む jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 確定する注文を作る CommandHandler。 */
  private final DraftOrderCommandHandler draftOrder;

  /** 注文を確定する CommandHandler。 */
  private final ConfirmOrderCommandHandler confirmOrder;

  /** 共通カラムの trace ID を作る observation registry。 */
  private final ObservationRegistry observationRegistry;

  /* package */ OrderConfirmedFixture(
      final DSLContext dsl,
      final DraftOrderCommandHandler draftOrder,
      final ConfirmOrderCommandHandler confirmOrder,
      final ObservationRegistry observationRegistry) {
    this.dsl = dsl;
    this.draftOrder = draftOrder;
    this.confirmOrder = confirmOrder;
    this.observationRegistry = observationRegistry;
  }

  /** 単価 120 円の商品を 2 個の下書きの注文を作り、注文 ID を返す。 */
  /* package */ String draftedOrderId() {
    final UUID productId = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    return observed(
        () ->
            draftOrder
                .handle(
                    new DraftOrderCommand(
                        UniqueCodes.next("C"),
                        List.of(new DraftOrderCommand.Line(productId.toString(), 2))))
                .orderId());
  }

  /** 作った直後（{@code lockNo} 1）の下書きの注文を確定する。 */
  /* package */ Object confirm(final String orderId) {
    return confirmOrder.handle(new ConfirmOrderCommand(orderId, new ExpectedLockNo(1)));
  }

  /** 共通カラムの trace ID を取れるよう、observation の中で処理を呼ぶ。 */
  /* package */ <T> T observed(final Supplier<T> action) {
    return Observation.createNotStarted("payment-test", observationRegistry).observe(action);
  }

  /** 注文を observation の中で確定し、出版の状態が条件を満たすまで待つ。 */
  /* package */ void confirmAndAwait(
      final Scenario scenario,
      final String orderId,
      final String expectation,
      final Predicate<RegistryState> done) {
    observed(
        () -> {
          await(
              scenario.stimulate(() -> confirm(orderId)),
              orderId,
              expectation,
              () -> registry(orderId),
              done);
          return null;
        });
  }

  /** 状態が条件を満たすまで待つ。満たさなければ注文 ID と最後に読んだ状態を出して失敗する。 */
  /* package */ static void await(
      final Scenario.When<?> when,
      final String orderId,
      final String expectation,
      final Supplier<RegistryState> read,
      final Predicate<RegistryState> done) {
    final AtomicReference<@Nullable RegistryState> last = new AtomicReference<>();
    try {
      when.andWaitForStateChange(
              () -> {
                final RegistryState state = read.get();
                last.set(state);
                return done.test(state);
              },
              Boolean.TRUE::equals)
          .andVerify(reached -> {});
    } catch (ConditionTimeoutException exception) {
      throw new AssertionError(
          "orderId=" + orderId + " の" + expectation + "を待ったが届かない: 最後の状態=" + last.get(), exception);
    }
  }

  /** 注文 ID の OrderConfirmed の出版の、未完了の行と archive の行を読む。 */
  /* package */ RegistryState registry(final String orderId) {
    final List<Row> incomplete =
        dsl.select(
                EVENT_PUBLICATION.STATUS,
                EVENT_PUBLICATION.COMPLETION_ATTEMPTS,
                EVENT_PUBLICATION.LAST_RESUBMISSION_DATE,
                EVENT_PUBLICATION.COMPLETION_DATE)
            .from(EVENT_PUBLICATION)
            .where(
                EVENT_PUBLICATION
                    .EVENT_TYPE
                    .eq(EVENT_TYPE)
                    .and(EVENT_PUBLICATION.SERIALIZED_EVENT.contains(orderId)))
            .fetch(Records.mapping(Row::new));
    final List<Row> archived =
        dsl.select(
                EVENT_PUBLICATION_ARCHIVE.STATUS,
                EVENT_PUBLICATION_ARCHIVE.COMPLETION_ATTEMPTS,
                EVENT_PUBLICATION_ARCHIVE.LAST_RESUBMISSION_DATE,
                EVENT_PUBLICATION_ARCHIVE.COMPLETION_DATE)
            .from(EVENT_PUBLICATION_ARCHIVE)
            .where(
                EVENT_PUBLICATION_ARCHIVE
                    .EVENT_TYPE
                    .eq(EVENT_TYPE)
                    .and(EVENT_PUBLICATION_ARCHIVE.SERIALIZED_EVENT.contains(orderId)))
            .fetch(Records.mapping(Row::new));
    // 未完了の行があればその値を、なければ archive の最後の行の値を状態とする。
    final Row row =
        incomplete.isEmpty()
            ? archived.isEmpty() ? new Row(null, null, null, null) : archived.getLast()
            : incomplete.getFirst();
    return new RegistryState(
        incomplete.size(),
        archived.size(),
        row.status(),
        row.attempts(),
        row.lastResubmissionDate(),
        row.completionDate());
  }

  /** イベント出版レジストリの 1 行の状態、回数、時刻。 */
  /* package */ record Row(
      @Nullable String status,
      @Nullable Integer attempts,
      @Nullable Instant lastResubmissionDate,
      @Nullable Instant completionDate) {}

  /**
   * 注文 ID の OrderConfirmed の出版の状態。
   *
   * @param incomplete 未完了の行の数
   * @param archived archive の行の数
   * @param status 未完了の行があればその状態、なければ archive の最後の行の状態
   * @param attempts 同じ行の {@code completion_attempts}
   * @param lastResubmissionDate 同じ行の {@code last_resubmission_date}
   * @param completionDate 同じ行の {@code completion_date}
   */
  /* package */ record RegistryState(
      int incomplete,
      int archived,
      @Nullable String status,
      @Nullable Integer attempts,
      @Nullable Instant lastResubmissionDate,
      @Nullable Instant completionDate) {}
}
