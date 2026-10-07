package com.example.demo.architecture;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Spring Modulith が認識するアプリケーションモジュールの境界を検証する。 */
class ApplicationModuleArchitectureTest {

  /** 依存の向きを確かめる機能モジュール。shared、jooq、error は基盤のモジュールなので含めない。 */
  private static final Set<String> FEATURES = Set.of("product", "ordering", "payment");

  /** モジュール間の循環、内部パッケージ参照、明示した許可依存への違反がないことを検証する。 */
  @Test
  @DisplayName("アプリケーションモジュールの境界を検証する")
  void verifiesApplicationModuleStructure() {
    ApplicationModules.of(DemoApplication.class).verify();
  }

  /** 循環にならない依存（payment から product など）は verify で検出できないため、機能モジュールの間の直接の依存を比べる。 */
  @Test
  @DisplayName("機能モジュールの間の直接の依存は ordering から product と payment から ordering だけである")
  void featureModulesDependOnlyInDeclaredDirections() {
    final ApplicationModules modules = ApplicationModules.of(DemoApplication.class);

    final Map<String, Set<String>> actual =
        FEATURES.stream()
            .collect(
                toMap(
                    Function.identity(),
                    name ->
                        modules
                            .getModuleByName(name)
                            .orElseThrow(() -> new AssertionError("モジュールがない: " + name))
                            .getDirectDependencies(modules)
                            .uniqueModules()
                            .map(module -> module.getIdentifier().toString())
                            .filter(FEATURES::contains)
                            .collect(toSet())));

    assertThat(actual)
        .as("機能モジュールごとの直接の依存先")
        .isEqualTo(
            Map.of(
                "product", Set.of(), "ordering", Set.of("product"), "payment", Set.of("ordering")));
  }
}
