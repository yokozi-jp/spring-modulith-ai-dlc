package com.example.demo.architecture;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.params.provider.Arguments;

/** 違反フィクスチャの基底パッケージと、規則とそれが検出するクラスの対応を表す行の作り方。 */
final class ViolatingFixtures {

  /** 違反フィクスチャの基底パッケージ。 */
  /* package */ static final String VIOLATING = "archfixture.violating";

  /** 違反フィクスチャのクラス名の接頭辞。 */
  private static final String VIOLATING_PREFIX = VIOLATING + ".";

  private ViolatingFixtures() {}

  /** 規則の名前、規則、違反フィクスチャのクラス名（基底パッケージより下）から、検出を確かめる行を作る。 */
  /* package */ static Arguments row(
      final String ruleName, final ArchRule rule, final String violatingClass) {
    return Arguments.of(ruleName, rule, VIOLATING_PREFIX + violatingClass);
  }
}
