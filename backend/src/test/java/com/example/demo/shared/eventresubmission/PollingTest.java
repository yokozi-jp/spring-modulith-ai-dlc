package com.example.demo.shared.eventresubmission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ロックと完了を確かめる間隔の待ち。 */
class PollingTest {

  @Test
  @DisplayName("待つ上限を、確かめる間隔で割った回数にする")
  void pollsWithinCountsIntervals() {
    assertThat(Polling.pollsWithin(Duration.ofSeconds(3))).isEqualTo(3);
    assertThat(Polling.pollsWithin(Duration.ZERO)).isZero();
  }

  @Test
  @DisplayName("int を超える上限は切り捨てず失敗する")
  void pollsWithinFailsBeyondInt() {
    assertThatThrownBy(() -> Polling.pollsWithin(Duration.ofSeconds(Integer.MAX_VALUE + 1L)))
        .isInstanceOf(ArithmeticException.class);
  }

  // 割り込みの扱いを確かめるため、テストのスレッド自身を割り込む。
  @SuppressWarnings("PMD.DoNotUseThreads")
  @Test
  @DisplayName("待っている間に割り込まれたら、割り込みを戻して失敗する")
  void sleepRestoresInterruptAndFails() {
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(Polling::sleep).isInstanceOf(IllegalStateException.class);
      assertThat(Thread.currentThread().isInterrupted()).as("割り込みの印").isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
