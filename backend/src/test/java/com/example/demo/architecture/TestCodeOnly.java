package com.example.demo.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** ArchUnit の解析対象を手書きのテストコードに限定する（{@link ProductionCodeOnly} の対）。 */
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestCodeOnly implements ImportOption {

  /** テストクラスの出力先を除外する ArchUnit 標準オプション。 */
  private static final ImportOption DO_NOT_INCLUDE_TESTS = new ImportOption.DoNotIncludeTests();

  @Override
  public boolean includes(final Location location) {
    // 標準オプションが「テストでない」と判定する場所を反転し、テスト出力だけを対象にする。
    return !DO_NOT_INCLUDE_TESTS.includes(location)
        && !location.contains("/jooq/")
        && !location.contains("/generated/");
  }
}
