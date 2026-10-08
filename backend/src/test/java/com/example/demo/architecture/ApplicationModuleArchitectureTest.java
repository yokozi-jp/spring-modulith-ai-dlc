package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.jooq.tables.EventPublication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/** Spring Modulith が認識するアプリケーションモジュールの境界を検証する。 */
class ApplicationModuleArchitectureTest {

  /** モジュール間の循環、内部パッケージ参照、明示した許可依存への違反がないことを検証する。 */
  @Test
  @DisplayName("アプリケーションモジュールの境界を検証する")
  void verifiesApplicationModuleStructure() {
    ApplicationModules.of(DemoApplication.class).verify();
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
