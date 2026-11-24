package com.example.demo.testkit;

import com.example.demo.DemoApplication;
import com.example.demo.architecture.ProductionCodeOnly;
import com.example.demo.shared.failure.FailureReason;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Arrays;
import java.util.List;

/**
 * プロダクションコードで {@link FailureReason} を実装する型と、その enum の定数を集める。
 *
 * <p>プロダクションコードが実行時に走査する方法とは別に、ArchUnit でクラスファイルを読んで集める。
 */
public final class FailureReasons {

  /** FailureReason を実装するプロダクションコードの型。import が遅いので、1 回だけ集める。 */
  private static final List<Class<?>> TYPES =
      new ClassFileImporter()
              .withImportOption(new ProductionCodeOnly())
              .importPackagesOf(DemoApplication.class)
              .stream()
              .filter(type -> type.isAssignableTo(FailureReason.class))
              .filter(type -> !type.isEquivalentTo(FailureReason.class))
              .<Class<?>>map(JavaClass::reflect)
              .toList();

  private FailureReasons() {}

  /** FailureReason を実装するプロダクションコードの型を返す。 */
  public static List<Class<?>> implementations() {
    return List.copyOf(TYPES);
  }

  /** FailureReason を実装する enum の定数をすべて返す。enum でない型は含まない。 */
  public static List<FailureReason> constants() {
    return TYPES.stream()
        .filter(Class::isEnum)
        .flatMap(type -> Arrays.stream(type.getEnumConstants()))
        .map(FailureReason.class::cast)
        .toList();
  }
}
