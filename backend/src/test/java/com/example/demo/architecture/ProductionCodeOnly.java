package com.example.demo.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** ArchUnit の解析対象を手書きのプロダクションコードに限定する。 */
public final class ProductionCodeOnly implements ImportOption {

  /** テストクラスの出力先を除外する ArchUnit 標準オプション。 */
  private static final ImportOption DO_NOT_INCLUDE_TESTS = new ImportOption.DoNotIncludeTests();

  @Override
  public boolean includes(final Location location) {
    return DO_NOT_INCLUDE_TESTS.includes(location) && isHandwritten(location);
  }

  /** jOOQ などの生成コードを除いた、手書きのコードの場所かを判定する。 */
  /* package */ static boolean isHandwritten(final Location location) {
    return !location.contains("/jooq/") && !location.contains("/generated/");
  }
}
