package com.example.demo.architecture;

import com.example.demo.DemoApplication;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** ArchUnit の解析対象を手書きのプロダクションコードに限定する。 */
public final class ProductionCodeOnly implements ImportOption {

  /** テストクラスの出力先を除外する ArchUnit 標準オプション。 */
  private static final ImportOption DO_NOT_INCLUDE_TESTS = new ImportOption.DoNotIncludeTests();

  /**
   * jOOQ の生成コードのパッケージの場所（backend/gradle/database.gradle の {@code packageName}）。
   *
   * <p>{@code /jooq/} だけで判定すると、{@code order.infrastructure.persistence.jooq} のような手書きのパッケージも
   * 対象外になり、書き込みの規則（TableWriterArchTest）を外れるため、基底パッケージの直下に限る。
   */
  private static final String GENERATED_JOOQ =
      "/" + DemoApplication.class.getPackageName().replace('.', '/') + "/jooq/";

  @Override
  public boolean includes(final Location location) {
    return DO_NOT_INCLUDE_TESTS.includes(location) && isHandwritten(location);
  }

  /** jOOQ の生成コードを除いた、手書きのコードの場所かを判定する。 */
  /* package */ static boolean isHandwritten(final Location location) {
    return !location.contains(GENERATED_JOOQ);
  }
}
