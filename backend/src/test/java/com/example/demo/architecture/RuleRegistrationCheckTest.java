package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.architecture.ArchTestFields.RuleField;
import com.example.demo.architecture.ViolatingFixtures.FixtureRow;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** {@link RuleRegistrationCheck} が、登録の誤りごとに規則の名前を出して報告し、正しい登録を通すことを確かめる。 */
class RuleRegistrationCheckTest {

  /** 登録する規則の名前。 */
  private static final String ALPHA_NAME = "alpha";

  /** 違反の種類ごとに行を分けて登録する規則の名前。 */
  private static final String BETA_NAME = "beta";

  /** 除外する規則の名前。 */
  private static final String GAMMA_NAME = "gamma";

  /** {@code @ArchTest} の規則のない名前。 */
  private static final String GONE = "gone";

  /** 登録する規則。 */
  private static final ArchRule ALPHA = rule("alpha の理由");

  /** 違反の種類ごとに行を分けて登録する規則。 */
  private static final ArchRule BETA = rule("beta の理由");

  /** 除外する規則。 */
  private static final ArchRule GAMMA = rule("gamma の理由");

  /** {@code @ArchTest} の規則のフィールド。 */
  private static final List<RuleField> FIELDS =
      List.of(
          new RuleField("AlphaArchTest", ALPHA_NAME, ALPHA),
          new RuleField("AlphaArchTest", BETA_NAME, BETA),
          new RuleField("GammaArchTest", GAMMA_NAME, GAMMA));

  /** 正しく登録した違反の行。 */
  private static final List<FixtureRow> ROWS =
      List.of(
          new FixtureRow(ALPHA_NAME, ALPHA, "A"), new FixtureRow(BETA_NAME + ": 種類", BETA, "B"));

  /** 正しく登録した、規約どおりのフィクスチャで確かめる規則。 */
  private static final Map<String, ArchRule> CONFORMING =
      Map.of(ALPHA_NAME, ALPHA, BETA_NAME, BETA);

  /** 正しく登録した除外。 */
  private static final Map<String, String> EXEMPTIONS = Map.of(GAMMA_NAME, "別のテストが確かめるため");

  @Test
  @DisplayName("両方に同じ規則で載るか、理由を付けて除外した登録は、誤りがない")
  void correctRegistrationHasNoProblems() {
    assertThat(new RuleRegistrationCheck(FIELDS, ROWS, CONFORMING, EXEMPTIONS).problems())
        .isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  @DisplayName("登録の誤りは、規則の名前と誤りの種類を一つの文で報告する")
  void eachProblemNamesTheRule(
      final String problem,
      final RuleRegistrationCheck check,
      final String ruleName,
      final String kind) {
    assertThat(check.problems())
        .as(problem)
        .singleElement()
        .asString()
        .startsWith(ruleName + "：")
        .contains(kind);
  }

  private static Stream<Arguments> eachProblemNamesTheRule() {
    return Stream.of(
        Arguments.of(
            "別のクラスに同じ名前の @ArchTest がある",
            new RuleRegistrationCheck(
                plus(FIELDS, new RuleField("OtherArchTest", ALPHA_NAME, ALPHA)),
                ROWS,
                CONFORMING,
                EXEMPTIONS),
            ALPHA_NAME,
            "[AlphaArchTest, OtherArchTest] に同じ名前"),
        Arguments.of(
            "違反の行がない",
            new RuleRegistrationCheck(FIELDS, ROWS.subList(0, 1), CONFORMING, EXEMPTIONS),
            BETA_NAME,
            "eachRuleDetectsItsViolatingFixture の行 にない"),
        Arguments.of(
            "ConformingRules にない",
            new RuleRegistrationCheck(FIELDS, ROWS, Map.of(BETA_NAME, BETA), EXEMPTIONS),
            ALPHA_NAME,
            "ConformingRules にない"),
        Arguments.of(
            "除外した規則が違反の行にある",
            new RuleRegistrationCheck(
                FIELDS, plus(ROWS, new FixtureRow(GAMMA_NAME, GAMMA, "C")), CONFORMING, EXEMPTIONS),
            GAMMA_NAME,
            "FixtureExemptions にあるのに eachRuleDetectsItsViolatingFixture の行 にある"),
        Arguments.of(
            "除外した規則が ConformingRules にある",
            new RuleRegistrationCheck(
                FIELDS,
                ROWS,
                Map.of(ALPHA_NAME, ALPHA, BETA_NAME, BETA, GAMMA_NAME, GAMMA),
                EXEMPTIONS),
            GAMMA_NAME,
            "FixtureExemptions にあるのに ConformingRules にある"),
        Arguments.of(
            "違反の行に @ArchTest のない名前がある",
            new RuleRegistrationCheck(
                FIELDS,
                plus(ROWS, new FixtureRow(GONE + ": 種類", ALPHA, "D")),
                CONFORMING,
                EXEMPTIONS),
            GONE,
            "eachRuleDetectsItsViolatingFixture の行 にあるが、この名前の @ArchTest の規則がない"),
        Arguments.of(
            "ConformingRules に @ArchTest のない名前がある",
            new RuleRegistrationCheck(
                FIELDS, ROWS, Map.of(ALPHA_NAME, ALPHA, BETA_NAME, BETA, GONE, ALPHA), EXEMPTIONS),
            GONE,
            "ConformingRules にあるが、この名前の @ArchTest の規則がない"),
        Arguments.of(
            "FixtureExemptions に @ArchTest のない名前がある",
            new RuleRegistrationCheck(
                FIELDS, ROWS, CONFORMING, Map.of(GAMMA_NAME, "理由", GONE, "理由")),
            GONE,
            "FixtureExemptions にあるが、この名前の @ArchTest の規則がない"),
        Arguments.of(
            "除外の理由が空",
            new RuleRegistrationCheck(FIELDS, ROWS, CONFORMING, Map.of(GAMMA_NAME, " ")),
            GAMMA_NAME,
            "FixtureExemptions の理由が空"),
        Arguments.of(
            "違反の行の名前の後ろに別の規則がある",
            new RuleRegistrationCheck(
                FIELDS,
                List.of(
                    new FixtureRow(ALPHA_NAME, ALPHA, "A"),
                    new FixtureRow(BETA_NAME + ": 種類", ALPHA, "B")),
                CONFORMING,
                EXEMPTIONS),
            BETA_NAME,
            "eachRuleDetectsItsViolatingFixture の行 の規則の because が AlphaArchTest.beta と違う"),
        Arguments.of(
            "ConformingRules の名前の後ろに別の規則がある",
            new RuleRegistrationCheck(
                FIELDS, ROWS, Map.of(ALPHA_NAME, GAMMA, BETA_NAME, BETA), EXEMPTIONS),
            ALPHA_NAME,
            "ConformingRules の規則の because が AlphaArchTest.alpha と違う"));
  }

  private static ArchRule rule(final String because) {
    return noClasses().should().haveSimpleName("Forbidden").because(because);
  }

  private static <T> List<T> plus(final List<T> list, final T element) {
    return Stream.concat(list.stream(), Stream.of(element)).toList();
  }
}
