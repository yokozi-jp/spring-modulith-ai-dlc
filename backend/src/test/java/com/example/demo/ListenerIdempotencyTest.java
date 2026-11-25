package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.payment.application.OrderConfirmedListenerExample;
import com.example.demo.testkit.CommittedDatabaseTest;
import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerIdempotencyChecker;
import com.example.demo.testkit.idempotency.ListenerIdempotencyConfig;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * すべての {@code @ApplicationModuleListener} に同じイベントを 2 回（順と同時）届け、2 回目が業務の表の行と外部システムの呼び出しを変えないことを確かめる。
 *
 * <p>リスナーを足したら、そのモジュールのテストに {@link ListenerExample} を作り、ここの {@code @Import} に足す。入力例の一覧は Spring
 * の文脈から受けるため、{@code @MethodSource} がインスタンスのメソッドを使えるよう、テストのインスタンスをクラスで 1 つにする。
 */
@CommittedDatabaseTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import({ListenerIdempotencyConfig.class, OrderConfirmedListenerExample.class})
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("listenerExamples")
  @DisplayName("同じイベントの順の 2 回目は、業務の表の行も外部システムの呼び出しも変えず、出版は 2 回とも完了する")
  void sequentialSecondDeliveryChangesNothing(final ListenerExample example) {
    assertThat(checker.checkSequential(example)).as("順の 2 回の違反").isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("listenerExamples")
  @DisplayName("1 回目のコミットの直前に届いた同時の 2 回目は、例外にならず、1 回だけ届けたときと同じ行と外部システムの呼び出しにする")
  void concurrentSecondDeliveryChangesNothing(final ListenerExample example) {
    assertThat(checker.checkConcurrent(example)).as("同時の 2 回の違反").isEmpty();
  }

  /** 入力例を、表示の名前をリスナーの ID にして返す。 */
  private Stream<Named<ListenerExample>> listenerExamples() {
    return examples.stream().map(example -> Named.of(example.listener().value(), example));
  }
}
