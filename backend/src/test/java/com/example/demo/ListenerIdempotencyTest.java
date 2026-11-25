package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.payment.application.OrderConfirmedListenerExample;
import com.example.demo.testkit.CommittedDatabaseTest;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerIdempotencyChecker;
import com.example.demo.testkit.idempotency.Violation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;

/**
 * すべての {@code @ApplicationModuleListener} に同じイベントを 2 回（順と同時）届け、2 回目が業務の表の行と外部システムの呼び出しを変えないことを確かめる。
 *
 * <p>リスナーを足したら、そのモジュールのテストに {@link ListenerExample} を作り、ここの {@code @Import} に足す。
 */
@CommittedDatabaseTest
@EnableScenarios
@Import({ListenerIdempotencyChecker.Config.class, OrderConfirmedListenerExample.class})
class ListenerIdempotencyTest {

  /** 検査。 */
  @Autowired private ListenerIdempotencyChecker checker;

  /** モジュールの入力例。 */
  @Autowired private List<ListenerExample> examples;

  @Test
  @DisplayName("すべてのリスナーに入力例があり、すべての入力例にリスナーがある")
  void everyListenerHasExample() {
    assertThat(checker.checkCoverage()).as("リスナー=%s", checker.allListeners()).isEmpty();
  }

  @Test
  @DisplayName("同じイベントの順の 2 回目は、業務の表の行も外部システムの呼び出しも変えず、出版は 2 回とも完了する")
  void sequentialSecondDeliveryChangesNothing(final Scenario scenario) {
    final List<Violation> violations = new ArrayList<>();
    for (final ListenerExample example : examples) {
      violations.addAll(checker.checkSequential(scenario, example));
    }

    assertThat(violations).as("順の 2 回の違反").isEmpty();
  }

  @Test
  @DisplayName("1 回目のコミットの直前に届いた同時の 2 回目は、例外にならず、1 回だけ届けたときと同じ行と外部システムの呼び出しにする")
  void concurrentSecondDeliveryChangesNothing(final Scenario scenario) {
    final List<Violation> violations = new ArrayList<>();
    for (final ListenerExample example : examples) {
      violations.addAll(checker.checkConcurrent(scenario, example));
    }

    assertThat(violations).as("同時の 2 回の違反").isEmpty();
  }
}
