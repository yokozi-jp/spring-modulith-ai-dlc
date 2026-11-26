package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * ArchUnit の各規則の because と PMD のカスタム規則の message が、理由、直し方、規約の文書のパスを持つことを確かめる。
 *
 * <p>ArchUnit の規則は、このパッケージで {@link ArchTest} を付けた {@link ArchRule} のフィールドから集める。PMD の規則は、{@code
 * backend/config/pmd} の ruleset で {@code name} を持つカスタム規則から集める。文は「{@code <理由>。直し方：<修正>。規約：<docs
 * のパス>}」の形にし、規約の文書のパスは「、」で区切って複数書いてよい。
 */
class ArchitectureRuleMessageTest {

  /** because の形。理由、直し方、規約の文書のパスの順に取り出す。 */
  private static final Pattern FORMAT = Pattern.compile("(?s)(.*?)直し方：(.+)。規約：(docs/.+)");

  /** 規約の文書のパスを解決するリポジトリのルート。Gradle はテストを backend で実行する。 */
  private static final Path REPOSITORY_ROOT = Path.of("..");

  /** カスタム規則の message を確かめる PMD の ruleset。 */
  private static final List<String> PMD_RULESETS =
      List.of("backend/config/pmd/ruleset.xml", "backend/config/pmd/test-ruleset.xml");

  /** 失敗したときに読む文書。 */
  private static final String GUIDE = "（docs/backend/architecture-tests.md）";

  @ParameterizedTest(name = "{0}")
  @MethodSource("ruleMessages")
  @DisplayName("ArchUnit の because と PMD の message は理由、直し方、規約の文書のパスを持つ")
  void eachRuleExplainsWhyAndHowToFix(final String ruleName, final String because) {
    assertThat(becauseOf(ruleName, because).group(1))
        .as(
            "%s の because（PMD は message）に理由がない。失敗した作業者が規則の目的を知れるようにするため。"
                + "docs か ADR にある理由を「直し方：」の前に「…ため。」と書く。"
                + "どこにも理由がなければ、先に理由を docs か ADR に書く%s",
            ruleName, GUIDE)
        .isNotBlank()
        .endsWith("。");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ruleMessages")
  @DisplayName("ArchUnit の because と PMD の message が示す規約の文書はリポジトリにある")
  void eachRuleReferencesExistingDocs(final String ruleName, final String because) {
    for (final String doc : becauseOf(ruleName, because).group(3).split("、", -1)) {
      assertThat(REPOSITORY_ROOT.resolve(doc))
          .as(
              "%s の「規約：」の %s がない。失敗した作業者が読む文書をたどれるようにするため。" + "リポジトリのルートからの docs/ のパスに直す%s",
              ruleName, doc, GUIDE)
          .isRegularFile();
    }
  }

  @Test
  @DisplayName("@ArchTest は ArchRule のフィールドにだけ付ける")
  void archTestsAreArchRuleFields() {
    final List<String> others =
        ArchTestFields.architectureClasses()
            .flatMap(javaClass -> javaClass.getMembers().stream())
            .filter(member -> member.isAnnotatedWith(ArchTest.class))
            .filter(member -> !ArchTestFields.isArchRuleField(member))
            .map(JavaMember::getFullName)
            .toList();

    assertThat(others)
        .as(
            "@ArchTest のメソッドと ArchTests のフィールドは because を検査できない。"
                + "規則を ArchRule の static final フィールドに書き直す%s",
            GUIDE)
        .isEmpty();
  }

  /** ArchUnit の規則の because と PMD のカスタム規則の message を、規則の名前と文の組で返す。 */
  private static Stream<Arguments> ruleMessages() {
    return Stream.concat(
        archRuleBecauses(),
        PMD_RULESETS.stream().flatMap(ArchitectureRuleMessageTest::pmdMessages));
  }

  /** このパッケージで {@link ArchTest} を付けた {@link ArchRule} のフィールドを、名前と最後の because の組で返す。 */
  private static Stream<Arguments> archRuleBecauses() {
    return ArchTestFields.ruleFields().stream()
        .map(field ->
            Arguments.of(
                field.owner() + "." + field.name(), ArchTestFields.lastBecause(field.rule())));
  }

  /**
   * PMD の ruleset から、{@code name} を持つカスタム規則を名前と message の組で返す。{@code ref} だけの規則は PMD の標準の規則なので除く。
   * message のないカスタム規則は空文字にして、形の検査で失敗させる。
   */
  private static Stream<Arguments> pmdMessages(final String path) {
    final Path file = REPOSITORY_ROOT.resolve(path);
    final List<Arguments> rules = new ArrayList<>();
    try {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      final NodeList elements =
          factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("rule");
      for (int i = 0; i < elements.getLength(); i++) {
        final Element rule = (Element) elements.item(i);
        if (rule.hasAttribute("name")) {
          rules.add(
              Arguments.of(
                  file.getFileName() + "." + rule.getAttribute("name"),
                  rule.getAttribute("message")));
        }
      }
    } catch (ParserConfigurationException | SAXException | IOException exception) {
      throw new IllegalStateException("PMD のカスタム規則を読めない: " + path, exception);
    }
    if (rules.isEmpty()) {
      throw new IllegalStateException("PMD のカスタム規則を読めない: " + path);
    }
    return rules.stream();
  }

  /** because か message が形に合うことを確かめてから、理由、直し方、規約の組を返す。 */
  private static Matcher becauseOf(final String ruleName, final String because) {
    assertThat(because)
        .as(
            "%s の because（PMD は message）が「<理由>。直し方：<修正>。規約：<docs のパス>」の形でない。"
                + "失敗した作業者が理由、直し方、読む文書を知れるようにするため。"
                + "ArchUnit の規則には .because(...) で、PMD のカスタム規則には message 属性で、この形の文を書く%s",
            ruleName, GUIDE)
        .matches(FORMAT);

    final Matcher matcher = FORMAT.matcher(because);
    assertThat(matcher.matches()).isTrue();
    return matcher;
  }
}
