package com.example.demo.eventcontract;

import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.architecture.ProductionCodeOnly;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.ApplicationModuleListener;
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
    final Set<Class<?>> listened = listenedEventTypes();
    EventContract.SERIALIZER_CONTEXT.run(context ->
        assertThat(EventContract.check(listened, "events", context.getBean(EventSerializer.class)))
            .as("リスナーが受ける型 %s と backend/src/test/resources/events/ の fixture", listened)
            .isEmpty());
  }

  /**
   * main のクラスを ArchUnit で読み、{@code @ApplicationModuleListener}
   * を付けたメソッドの引数の型を全件集める。出版を保存するのは、リスナーのある型だけである。
   *
   * <p>{@code testkit.idempotency.ListenerId} と同じく、{@code @ApplicationModuleListener}
   * を合成した注釈を付けたメソッドも集める。
   */
  private static Set<Class<?>> listenedEventTypes() {
    return new ClassFileImporter()
        .withImportOption(new ProductionCodeOnly()).importPackagesOf(DemoApplication.class).stream()
            .flatMap(type -> type.getMethods().stream())
            .filter(method ->
                method.isAnnotatedWith(ApplicationModuleListener.class)
                    || method.isMetaAnnotatedWith(ApplicationModuleListener.class))
            .flatMap(method -> method.getRawParameterTypes().stream())
            .map(JavaClass::reflect)
            .collect(toUnmodifiableSet());
  }
}
