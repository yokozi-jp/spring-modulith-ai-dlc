package com.example.demo.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.order.OrderConfirmed;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.ResubmissionOptions;

/** 手で確かめるための再投入の入口が、OrderConfirmed の失敗した出版だけを 1 回再投入することを検証する。 */
class ResubmitFailedPaymentsRunnerTest {

  /** 出版の時刻。 */
  private static final Instant PUBLISHED_AT = Instant.parse("2026-10-06T01:02:03Z");

  /** 出版の ID。 */
  private static final UUID PUBLICATION_ID =
      UUID.fromString("00000000-0000-4000-8000-000000000001");

  @Test
  @DisplayName("resubmit を 1 回呼び、一度に 100 件まで読み、OrderConfirmed の出版だけを通す")
  void resubmitsOnlyOrderConfirmedOnce() {
    final List<ResubmissionOptions> calls = new CopyOnWriteArrayList<>();
    final ResubmitFailedPaymentsRunner runner = new ResubmitFailedPaymentsRunner(calls::add);

    runner.run(new DefaultApplicationArguments());

    assertThat(calls).as("resubmit の呼び出し").hasSize(1);
    final ResubmissionOptions options = calls.getFirst();
    assertThat(options.getBatchSize()).isEqualTo(100);
    assertThat(
            options
                .getFilter()
                .test(publication(new OrderConfirmed(UUID.randomUUID().toString(), PUBLISHED_AT))))
        .as("OrderConfirmed の出版")
        .isTrue();
    assertThat(options.getFilter().test(publication("other event")))
        .as("OrderConfirmed でない出版")
        .isFalse();
  }

  private static EventPublication publication(final Object event) {
    return new FailedPublication(event);
  }

  /**
   * テストの中だけの失敗した出版。
   *
   * @param event 出版したイベント
   */
  private record FailedPublication(Object event) implements EventPublication {

    @Override
    public UUID getIdentifier() {
      return PUBLICATION_ID;
    }

    @Override
    public Object getEvent() {
      return event;
    }

    @Override
    public Instant getPublicationDate() {
      return PUBLISHED_AT;
    }

    @Override
    public Optional<Instant> getCompletionDate() {
      return Optional.empty();
    }

    @Override
    public Status getStatus() {
      return Status.FAILED;
    }

    @Override
    public Instant getLastResubmissionDate() {
      return PUBLISHED_AT;
    }

    @Override
    public int getCompletionAttempts() {
      return 1;
    }
  }
}
