package com.example.demo.eventcontract;

import static java.util.stream.Collectors.toUnmodifiableSet;

import com.example.demo.DemoApplication;
import com.example.demo.architecture.ProductionCodeOnly;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Set;
import org.springframework.modulith.events.ApplicationModuleListener;

/** main のクラスの {@code @ApplicationModuleListener} が受けるイベントの型。出版を保存するのは、リスナーのある型だけである。 */
final class ListenedEvents {

  private ListenedEvents() {}

  /** main のクラスを ArchUnit で読み、{@code @ApplicationModuleListener} を付けたメソッドの引数の型を全件集める。 */
  /* package */ static Set<Class<?>> inProductionCode() {
    return new ClassFileImporter()
        .withImportOption(new ProductionCodeOnly()).importPackagesOf(DemoApplication.class).stream()
            .flatMap(type -> type.getMethods().stream())
            .filter(method -> method.isAnnotatedWith(ApplicationModuleListener.class))
            .flatMap(method -> method.getRawParameterTypes().stream())
            .map(JavaClass::reflect)
            .collect(toUnmodifiableSet());
  }
}
