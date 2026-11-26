package com.example.demo.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.params.provider.Arguments;

/** 違反フィクスチャの基底パッケージ、import 結果、規則とそれが検出するクラスの対応を表す行の作り方を、フィクスチャを読むテストで共有する。 */
// JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
@SuppressWarnings("PMD.LooseCoupling")
final class ViolatingFixtures {

  /** 違反フィクスチャの基底パッケージ。 */
  /* package */ static final String VIOLATING = "archfixture.violating";

  /** 違反フィクスチャのクラス名の接頭辞。 */
  private static final String VIOLATING_PREFIX = VIOLATING + ".";

  /**
   * 違反フィクスチャの import 結果。
   *
   * <p>import はクラスパス全体を走査して遅く、繰り返すと全体の大半を占める。規則の検査は import 結果を読むだけなので、全テストで共有する。
   */
  /* package */ static final JavaClasses VIOLATING_CLASSES =
      new ClassFileImporter().importPackages(VIOLATING);

  private ViolatingFixtures() {}

  /** 行の名前、規則、違反フィクスチャのクラス名（基底パッケージより下）から、検出を確かめる行を作る。 */
  /* package */ static FixtureRow row(
      final String name, final ArchRule rule, final String violatingClass) {
    return new FixtureRow(name, rule, VIOLATING_PREFIX + violatingClass);
  }

  /**
   * 規則が違反フィクスチャのクラスを検出することを確かめる行。JUnit には名前、規則、クラス名の順の引数として渡す。
   *
   * @param name 行の名前。{@code @ArchTest} のフィールドの名前か、その後に {@code ": "} と違反の種類を足したもの
   * @param rule 確かめる規則
   * @param violatingClass 失敗のメッセージに含まれる、違反フィクスチャのクラスの完全修飾名
   */
  /* package */ record FixtureRow(String name, ArchRule rule, String violatingClass)
      implements Arguments {

    /** 行の名前から違反の種類を除いた、{@code @ArchTest} のフィールドの名前を返す。 */
    /* package */ String ruleName() {
      return name.split(":", 2)[0];
    }

    @Override
    public Object[] get() {
      return new Object[] {name, rule, violatingClass};
    }
  }
}
