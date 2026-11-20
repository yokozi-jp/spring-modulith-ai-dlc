package com.example.demo.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

/** 違反フィクスチャの基底パッケージと import 結果を、フィクスチャを読むテストで共有する。 */
// JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
@SuppressWarnings("PMD.LooseCoupling")
final class ViolatingFixtures {

  /** 違反フィクスチャの基底パッケージ。 */
  /* package */ static final String VIOLATING = "archfixture.violating";

  /**
   * 違反フィクスチャの import 結果。
   *
   * <p>import はクラスパス全体を走査して遅く、繰り返すと全体の大半を占める。規則の検査は import 結果を読むだけなので、全テストで共有する。
   */
  /* package */ static final JavaClasses VIOLATING_CLASSES =
      new ClassFileImporter().importPackages(VIOLATING);

  private ViolatingFixtures() {}
}
