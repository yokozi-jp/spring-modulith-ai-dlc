package com.example.demo.eventcontract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.core.EventSerializer;

/**
 * main のリスナーが受けるすべてのイベントの型で、{@code events/} の過去のすべての版の fixture を読めることを確かめる。
 *
 * <p>イベントを足すか変えるときは、同じ変更で次の版の fixture を足し、過去の fixture は書き換えも消しもしない。
 */
// ApplicationContextRunner の run に渡す callback の中で assert するため、PMD はテストメソッドの assert を検出できない。
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class EventContractTest {

  @Test
  @DisplayName("リスナーが受けるイベントの型は、v1 から続くすべての版の fixture を読み、同じ JSON に書き直す")
  void listenedEventsReadEveryFixture() {
    final Set<Class<?>> listened = ListenedEvents.inProductionCode();
    EventContract.SERIALIZER_CONTEXT.run(context ->
        assertThat(
                EventContract.checkDirectory(
                    listened, "events", context.getBean(EventSerializer.class)))
            .as("リスナーが受ける型 %s と backend/src/test/resources/events/ の fixture", listened)
            .isEmpty());
  }
}
