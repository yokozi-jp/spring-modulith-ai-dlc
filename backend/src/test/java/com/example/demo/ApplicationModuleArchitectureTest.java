package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Spring Modulith が認識するアプリケーションモジュールの境界を検証する。 */
class ApplicationModuleArchitectureTest {

  /** モジュール間の循環、内部パッケージ参照、明示した許可依存への違反がないことを検証する。 */
  @Test
  void verifiesApplicationModuleStructure() {
    ApplicationModules.of(DemoApplication.class).verify();
  }
}
