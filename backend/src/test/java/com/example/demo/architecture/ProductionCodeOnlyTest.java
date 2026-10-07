package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.DefaultCatalog;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ProductionCodeOnly} が jOOQ の生成コードだけを外し、手書きのコードを外さないことを確かめる。 */
class ProductionCodeOnlyTest {

  /** jOOQ の生成コードのソースの置き場所（backend/gradle/database.gradle の {@code directory}）。 */
  private static final Path GENERATED_SOURCES = Path.of("src", "generated", "jooq");

  @Test
  @DisplayName("jooq という名前の手書きのパッケージは ArchUnit の規則の対象にする")
  void includesHandwrittenJooqSubPackage() {
    final Location handwritten =
        Location.of(
            URI.create(
                "file:/app/backend/build/classes/java/main/com/example/demo/"
                    + "order/infrastructure/persistence/jooq/JooqOrderRepository.class"));

    assertThat(ProductionCodeOnly.isHandwritten(handwritten)).isTrue();
  }

  @Test
  @DisplayName("jOOQ の生成コードは ArchUnit の規則の対象から外す")
  void excludesGeneratedJooqCode() throws URISyntaxException {
    final Location generated =
        Location.of(
            Objects.requireNonNull(DefaultCatalog.class.getResource("DefaultCatalog.class"))
                .toURI());

    assertThat(ProductionCodeOnly.isHandwritten(generated)).isFalse();
  }

  /**
   * 生成先のパッケージに手書きのクラスを置くと、{@link ProductionCodeOnly} がそれを外し、すべての規則を逃れる。
   *
   * <p>ディレクトリではなくクラスのソースファイル名で照らすため、別のディレクトリで生成先のパッケージを宣言したクラスも検出する。
   */
  @Test
  @DisplayName("jOOQ の生成先のパッケージの本番のクラスは、すべて src/generated/jooq の生成コードである")
  // JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
  @SuppressWarnings("PMD.LooseCoupling")
  void generatedJooqPackageHoldsOnlyGeneratedCode() throws URISyntaxException {
    final Path generatedSources = backendDirectory().resolve(GENERATED_SOURCES);
    final JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(DefaultCatalog.class.getPackageName());
    assertThat(classes.contain(DefaultCatalog.class)).as("生成コードの DefaultCatalog を読み込めたか").isTrue();

    final List<String> notGenerated =
        classes.stream()
            .filter(javaClass -> !isGenerated(generatedSources, javaClass))
            .map(JavaClass::getName)
            .toList();

    assertThat(notGenerated)
        .as(
            "%s には jOOQ のコード生成の出力だけを置く（ADR-054）。手書きのクラスは別のパッケージへ移す。照らした生成コード: %s",
            DefaultCatalog.class.getPackageName(), generatedSources)
        .isEmpty();
  }

  /** クラスのソースファイルが生成コードの置き場所にあるかを判定する。ソースファイル名を持たないクラスは生成コードとみなさない。 */
  private static boolean isGenerated(final Path generatedSources, final JavaClass javaClass) {
    final Path packageDirectory =
        generatedSources.resolve(javaClass.getPackageName().replace('.', '/'));
    return javaClass
        .getSource()
        .flatMap(Source::getFileName)
        .map(fileName -> Files.isRegularFile(packageDirectory.resolve(fileName)))
        .orElse(false);
  }

  /** 本番のクラスの出力先から親をたどり、生成コードの置き場所を持つディレクトリ（backend）を返す。作業ディレクトリに依存しない。 */
  private static Path backendDirectory() throws URISyntaxException {
    final Path classesRoot =
        Path.of(
            Objects.requireNonNull(DefaultCatalog.class.getProtectionDomain().getCodeSource())
                .getLocation()
                .toURI());
    for (Path directory = classesRoot; directory != null; directory = directory.getParent()) {
      if (Files.isDirectory(directory.resolve(GENERATED_SOURCES))) {
        return directory;
      }
    }
    throw new AssertionError(GENERATED_SOURCES + " を持つ親ディレクトリが見つからない: classesRoot=" + classesRoot);
  }
}
