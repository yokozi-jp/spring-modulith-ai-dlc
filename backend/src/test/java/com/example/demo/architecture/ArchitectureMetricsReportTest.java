package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.library.metrics.ArchitectureMetrics;
import com.tngtech.archunit.library.metrics.ComponentDependencyMetrics;
import com.tngtech.archunit.library.metrics.LakosMetrics;
import com.tngtech.archunit.library.metrics.MetricsComponent;
import com.tngtech.archunit.library.metrics.MetricsComponents;
import com.tngtech.archunit.library.metrics.VisibilityMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Package by feature の機能パッケージ単位でアーキテクチャ指標を出力する。 */
class ArchitectureMetricsReportTest {

  /** Spring Boot アプリケーションのベースパッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName();

  /** アーキテクチャ指標レポートの出力ディレクトリ。 */
  private static final Path REPORT_DIRECTORY = Path.of("build", "reports", "architecture-metrics");

  /** アーキテクチャ指標レポートの出力先。 */
  private static final Path REPORT_PATH = REPORT_DIRECTORY.resolve("architecture-metrics.txt");

  /** 均衡二分木を基準とした正規化累積コンポーネント依存の上限。 */
  private static final double MAX_NORMALIZED_CUMULATIVE_COMPONENT_DEPENDENCY = 1.0D;

  /** 現在の外部可視型数 7、全型数 13 を基準とする global relative visibility の上限。 */
  private static final double MAX_GLOBAL_RELATIVE_VISIBILITY = 7.0D / 13.0D;

  /** Lakos、コンポーネント依存、可視性の各指標を計算してレポートへ保存する。 */
  @Test
  @DisplayName("アーキテクチャ指標レポートを生成する")
  // JavaClasses と MetricsComponents は ArchitectureMetrics の公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void writesArchitectureMetricsReport() throws IOException {
    final JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ProductionCodeOnly())
            .importPackages(BASE_PACKAGE);
    final MetricsComponents<JavaClass> components =
        MetricsComponents.from(classes, ArchitectureMetricsReportTest::componentIdentifier);
    final Set<String> componentIdentifiers = new TreeSet<>();
    for (final MetricsComponent<JavaClass> nullableComponent : components) {
      final MetricsComponent<JavaClass> component =
          Objects.requireNonNull(nullableComponent, "MetricsComponents must not contain null");
      componentIdentifiers.add(
          Objects.requireNonNull(
              component.getIdentifier(), "MetricsComponent identifier must not be null"));
    }

    final LakosMetrics lakosMetrics = ArchitectureMetrics.lakosMetrics(components);
    final ComponentDependencyMetrics dependencyMetrics =
        ArchitectureMetrics.componentDependencyMetrics(components);
    final VisibilityMetrics visibilityMetrics = ArchitectureMetrics.visibilityMetrics(components);
    final String report =
        renderReport(componentIdentifiers, lakosMetrics, dependencyMetrics, visibilityMetrics);

    Files.createDirectories(REPORT_DIRECTORY);
    Files.writeString(REPORT_PATH, report, StandardCharsets.UTF_8);

    assertThat(Files.readString(REPORT_PATH, StandardCharsets.UTF_8))
        .contains("Architecture Metrics", "component=bootstrap", "CCD=", "GRV=");
    assertThat(lakosMetrics.getNormalizedCumulativeComponentDependency())
        .as("NCCD は均衡二分木を基準とする上限 %s 以下", MAX_NORMALIZED_CUMULATIVE_COMPONENT_DEPENDENCY)
        .isLessThanOrEqualTo(MAX_NORMALIZED_CUMULATIVE_COMPONENT_DEPENDENCY);
    // ponytail: GRV は component 間の増減を相殺できる。必要になったら module ごとの公開 API 規則へ置き換える。
    assertThat(visibilityMetrics.getGlobalRelativeVisibility())
        .as("GRV は現在の公開面積比 %s 以下", MAX_GLOBAL_RELATIVE_VISIBILITY)
        .isLessThanOrEqualTo(MAX_GLOBAL_RELATIVE_VISIBILITY);
  }

  private static String componentIdentifier(final JavaClass javaClass) {
    final String packageName = javaClass.getPackageName();
    if (BASE_PACKAGE.equals(packageName)) {
      return "bootstrap";
    }

    final String relativePackage = packageName.substring(BASE_PACKAGE.length() + 1);
    final int separator = relativePackage.indexOf('.');
    return separator < 0 ? relativePackage : relativePackage.substring(0, separator);
  }

  private static String renderReport(
      final Set<String> componentIdentifiers,
      final LakosMetrics lakosMetrics,
      final ComponentDependencyMetrics dependencyMetrics,
      final VisibilityMetrics visibilityMetrics) {
    final StringBuilder report = new StringBuilder();
    appendLine(report, "Architecture Metrics");
    appendLine(report, "components=%d", componentIdentifiers.size());
    appendLine(report, "CCD=%d", lakosMetrics.getCumulativeComponentDependency());
    appendLine(report, "ACD=%.6f", lakosMetrics.getAverageComponentDependency());
    appendLine(report, "RACD=%.6f", lakosMetrics.getRelativeAverageComponentDependency());
    appendLine(report, "NCCD=%.6f", lakosMetrics.getNormalizedCumulativeComponentDependency());
    appendLine(report, "ARV=%.6f", visibilityMetrics.getAverageRelativeVisibility());
    appendLine(report, "GRV=%.6f", visibilityMetrics.getGlobalRelativeVisibility());

    for (final String component : componentIdentifiers) {
      appendLine(report, "");
      appendLine(report, "component=%s", component);
      appendLine(report, "Ce=%d", dependencyMetrics.getEfferentCoupling(component));
      appendLine(report, "Ca=%d", dependencyMetrics.getAfferentCoupling(component));
      appendLine(report, "I=%.6f", dependencyMetrics.getInstability(component));
      appendLine(report, "A=%.6f", dependencyMetrics.getAbstractness(component));
      appendLine(
          report, "D=%.6f", dependencyMetrics.getNormalizedDistanceFromMainSequence(component));
      appendLine(report, "RV=%.6f", visibilityMetrics.getRelativeVisibility(component));
    }
    return report.toString();
  }

  @FormatMethod
  private static void appendLine(
      final StringBuilder destination,
      @FormatString final String format,
      final Object... arguments) {
    destination
        .append(String.format(Locale.ROOT, format, arguments))
        .append(System.lineSeparator());
  }
}
