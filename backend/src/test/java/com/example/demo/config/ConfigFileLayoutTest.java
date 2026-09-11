package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spring の設定ファイル配置を規約どおりに制限する。
 *
 * <p>設定は {@code .env} 等の環境変数で一元管理する方針であり、プロファイル別の {@code application-*.yaml} を増やさない。AWS
 * 環境と合わせやすくし、環境変数とシークレットを一箇所で管理するため、次を機械的に強制する。
 *
 * <ul>
 *   <li>{@code src/main/resources} には {@code application.yaml} だけを許可する。{@code
 *       application-<profile>.yaml} や {@code application.properties} など、ほかの {@code application*}
 *       設定ファイルは置かない。
 *   <li>{@code src/test/resources} には {@code application*} の設定ファイルを一切置かない。テストの設定差分も {@code
 *       .env.test} と環境変数で与える。
 * </ul>
 *
 * <p>この検査対象は設定リソース（ファイルの存在）であり Java バイトコードではないため、ArchUnit ではなくリソースディレクトリの走査で強制する。
 */
class ConfigFileLayoutTest {

  /** {@code application}、{@code application-*}、{@code application.*} 形式の設定ファイル名にマッチする。 */
  private static final Pattern APPLICATION_CONFIG_FILE =
      Pattern.compile("^application(-.*)?\\.(ya?ml|properties)$");

  /** main で唯一許可する設定ファイル名。 */
  private static final String ALLOWED_MAIN_CONFIG = "application.yaml";

  /** モジュールルート（backend）からの main リソースディレクトリの相対パス。 */
  private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

  /** モジュールルート（backend）からの test リソースディレクトリの相対パス。 */
  private static final Path TEST_RESOURCES = Path.of("src", "test", "resources");

  @Test
  void mainResourcesContainOnlyTheSingleAllowedApplicationConfig() {
    final List<String> disallowed =
        applicationConfigFilesUnder(MAIN_RESOURCES).stream()
            .filter(path -> !ALLOWED_MAIN_CONFIG.equals(path.getFileName().toString()))
            .map(path -> path.toString())
            .toList();

    assertThat(disallowed)
        .as(
            "src/main/resources には %s だけを許可する。プロファイル別設定や .properties は作らず、"
                + "設定は .env 等の環境変数で一元管理する。違反ファイル: %s",
            ALLOWED_MAIN_CONFIG, disallowed)
        .isEmpty();
  }

  @Test
  void testResourcesContainNoApplicationConfig() {
    final List<String> disallowed =
        applicationConfigFilesUnder(TEST_RESOURCES).stream().map(path -> path.toString()).toList();

    assertThat(disallowed)
        .as(
            "src/test/resources には application 系の設定ファイルを置かない。"
                + "テストの設定差分も .env.test と環境変数で与える。違反ファイル: %s",
            disallowed)
        .isEmpty();
  }

  /** 指定ディレクトリ配下（再帰）の {@code application*} 設定ファイルを列挙する。ディレクトリが無ければ空リストを返す。 */
  private static List<Path> applicationConfigFilesUnder(final Path resourceDir) {
    if (!Files.isDirectory(resourceDir)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.walk(resourceDir)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> APPLICATION_CONFIG_FILE.matcher(path.getFileName().toString()).matches())
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("リソースディレクトリの走査に失敗しました: " + resourceDir, e);
    }
  }
}
