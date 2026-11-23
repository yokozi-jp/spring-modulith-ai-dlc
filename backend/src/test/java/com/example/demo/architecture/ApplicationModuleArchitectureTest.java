package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.jooq.modulith.tables.EventPublication;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** Spring Modulith が認識するアプリケーションモジュールの境界を検証する。 */
class ApplicationModuleArchitectureTest {

  /** 宣言のないモジュールがあるときの失敗の説明。直し方を含める。 */
  private static final String MISSING_DECLARATION_MESSAGE =
      "allowedDependencies を宣言していないモジュールがある。モジュールのルートの package-info.java に"
          + " @ApplicationModule(allowedDependencies = ...) を書く（ADR-118）";

  /** モジュール間の循環、内部パッケージ参照、明示した許可依存への違反がないことを検証する。 */
  @Test
  @DisplayName("アプリケーションモジュールの境界を検証する")
  void verifiesApplicationModuleStructure() {
    ApplicationModules.of(DemoApplication.class).verify();
  }

  /**
   * 宣言のないモジュールを違反にするテストを、Spring Modulith の設定では作れないため手で書く。
   *
   * <p>{@code verify()} は {@code allowedDependencies} を宣言したモジュールの依存先だけを確かめ、宣言のないモジュール（既定は {@code
   * OPEN_TOKEN}）の依存先は確かめない。{@code VerificationOptions} は ArchUnit
   * の規則を足せるだけで、宣言のないことを違反にする設定がない（ADR-118）。
   */
  @Test
  @DisplayName("OPEN 以外のすべてのモジュールが allowedDependencies を宣言している")
  void everyClosedModuleDeclaresAllowedDependencies() {
    assertThat(modulesWithoutAllowedDependencies(ApplicationModules.of(DemoApplication.class)))
        .as(MISSING_DECLARATION_MESSAGE)
        .isEmpty();
  }

  @Test
  @DisplayName("allowedDependencies のないモジュールは名前を挙げて検出する")
  void detectsModuleWithoutAllowedDependencies() {
    assertThat(
            modulesWithoutAllowedDependencies(
                ApplicationModules.of(
                    "archfixture.modulegate", new ImportOption.DoNotIncludeJars())))
        .containsExactly("undeclared");
  }

  private static List<String> modulesWithoutAllowedDependencies(final ApplicationModules modules) {
    return modules.stream()
        .filter(module -> !module.isOpen())
        .filter(module ->
            module
                .getBasePackage()
                .findAnnotation(org.springframework.modulith.ApplicationModule.class)
                .map(declared -> List.of(declared.allowedDependencies()))
                .filter(names ->
                    !names.equals(
                        List.of(org.springframework.modulith.ApplicationModule.OPEN_TOKEN)))
                .isEmpty())
        .map(module -> module.getIdentifier().toString())
        .toList();
  }

  @Test
  @DisplayName("jOOQ の生成物のモジュールは OPEN で、サブパッケージの生成型も公開する（ADR-067）")
  void jooqModuleExposesGeneratedTypes() {
    final ApplicationModule jooq =
        ApplicationModules.of(DemoApplication.class)
            .getModuleByName("jooq")
            .orElseThrow(() -> new AssertionError("モジュールが見つからない: name=jooq"));

    assertThat(jooq.isExposed(EventPublication.class))
        .as("package-info.java の @ApplicationModule(type = OPEN) が jooqCodegen の doLast で書かれていること")
        .isTrue();
  }
}
