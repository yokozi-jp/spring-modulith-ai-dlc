package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Spring Modulith が認識したアプリケーションモジュールのドキュメントを生成する。 */
class ApplicationModuleDocumentationTest {

  /** コンポーネント図、モジュール別の図、Module Canvas、集約 AsciiDoc を生成する。 */
  @Test
  @DisplayName("アプリケーションモジュールのドキュメントを生成する")
  void writesApplicationModuleDocumentation() throws IOException {
    final Path outputDirectory = Path.of("build", "spring-modulith-docs");
    final Path components = outputDirectory.resolve("components.puml");
    final Path allDocumentation = outputDirectory.resolve("all-docs.adoc");
    Files.deleteIfExists(components);
    Files.deleteIfExists(allDocumentation);

    final ApplicationModules modules = ApplicationModules.of(DemoApplication.class);
    new Documenter(modules)
        .writeModulesAsPlantUml()
        .writeIndividualModulesAsPlantUml()
        .writeModuleCanvases()
        .writeAggregatingDocument();

    assertThat(Files.readString(components)).contains("@startuml", "@enduml");
    assertThat(Files.readString(allDocumentation)).contains("plantuml::components.puml");
  }
}
