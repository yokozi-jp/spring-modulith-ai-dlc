package com.example.demo.persistence.conventions;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Liquibaseのchangesetのファイルを、DBに接続せずに静的に検査する。
 *
 * <p>ファイル名の順にchangesetを読み、構造の規則をすべてのchangesetに、テーブルの規則を{@code
 * modulith}以外のスキーマのテーブルに適用する。テーブルの最終形は{@link ChangesetReplay}が組み立てる。
 */
final class ChangesetLint {

  /** 本番のchangesetのディレクトリ（backendのモジュールルートからの相対パス）。 */
  /* package */ static final Path PRODUCTION_CHANGESETS =
      Path.of("src", "main", "resources", "db", "changelog", "changesets");

  /** フレームワークがスキーマを定めるため、テーブルの規則を適用しないスキーマ。 */
  /* package */ static final Set<String> FRAMEWORK_SCHEMAS = Set.of("modulith");

  /** 001のchangesetのid。 */
  private static final String EVENT_PUBLICATION_CHANGESET = "001-create-event-publication-tables";

  /** 本番のchangesetの許可リスト。 */
  /* package */ static final List<AllowlistEntry> ALLOWLIST =
      List.of(
          eventPublicationSql(
              EVENT_PUBLICATION_CHANGESET + ": CREATE SCHEMA modulith AUTHORIZATION CURRENT_USER;",
              "Liquibase OSS 5.0.4にはスキーマを作るChange Typeがない。"
                  + "ADR-011はモジュールのスキーマをchangesetで作ると定めている。"),
          eventPublicationSql(
              EVENT_PUBLICATION_CHANGESET
                  + ": GRANT USAGE ON SCHEMA modulith TO \"${appDatabaseUsername}\";",
              "Liquibase OSSにはGRANTのChange Typeがない。"
                  + "ADR-011は、各changesetが自スキーマのUSAGEをアプリロールに与えると定めている。"),
          eventPublicationSql(
              EVENT_PUBLICATION_CHANGESET
                  + ": GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE modulith.event_publication,"
                  + " modulith.event_publication_archive TO \"${appDatabaseUsername}\";",
              "Liquibase OSSにはGRANTのChange Typeがない。"
                  + "アプリロールへテーブルごとにDMLを与える（ADR-011、docs/database/connections.md）。"),
          eventPublicationSql(
              EVENT_PUBLICATION_CHANGESET + ": DROP SCHEMA modulith;",
              "CREATE SCHEMAのrollbackであり、Liquibase OSSにはスキーマを削除するChange Typeがない。"));

  private ChangesetLint() {
    // 静的メソッドだけを持つ。
  }

  /** 001の任意のSQL（M17）を許可リストに載せる。 */
  private static AllowlistEntry eventPublicationSql(final String target, final String reason) {
    return new AllowlistEntry("M17", target, reason);
  }

  /**
   * ディレクトリのchangesetを検査する。
   *
   * @param changesetsDir changesetのディレクトリ
   * @param allowlist 許可リスト
   * @return 許可リストで抑止されなかった違反と、使われなかった許可リストの項目
   */
  /* package */ static List<ConventionViolation> lint(
      final Path changesetsDir, final List<AllowlistEntry> allowlist) {
    final List<String> fileNames = fileNames(changesetsDir);
    final List<ConventionViolation> found =
        new ArrayList<>(ChangesetStructureRules.fileNames(fileNames));
    final List<Changeset> changesets = new ArrayList<>();
    for (final String fileName : fileNames) {
      if (fileName.endsWith(".yaml")) {
        load(changesetsDir, fileName, found).ifPresent(changesets::add);
      }
    }
    changesets.forEach(changeset -> found.addAll(ChangesetStructureRules.check(changeset)));
    final ChangesetReplay.Result replay = ChangesetReplay.replay(changesets);
    found.addAll(
        ChangesetTableRules.check(
            replay.tables().stream().filter(table -> isBusinessSchema(table.schema())).toList()));
    found.addAll(ChangesetChangeRules.check(changesets, replay));
    found.addAll(ChangesetOnlineChangeRules.check(changesets, replay));
    return AllowlistEntry.apply(found, allowlist);
  }

  /**
   * テーブルの規則を適用するスキーマか。{@code schemaName}がない変更（空文字）も適用する。
   *
   * @param schema スキーマ名
   * @return {@link #FRAMEWORK_SCHEMAS}に含まれなければ{@code true}
   */
  /* package */ static boolean isBusinessSchema(final String schema) {
    return !FRAMEWORK_SCHEMAS.contains(schema);
  }

  private static List<String> fileNames(final Path changesetsDir) {
    try (Stream<Path> entries = Files.list(changesetsDir)) {
      return entries.map(path -> path.getFileName().toString()).sorted().toList();
    } catch (final IOException e) {
      throw new UncheckedIOException("changesetのディレクトリを読めない: " + changesetsDir, e);
    }
  }

  /** ファイルを読み、最初の{@code changeSet}を返す。1ファイル1 changesetでなければ違反を加える。 */
  private static Optional<Changeset> load(
      final Path changesetsDir, final String fileName, final List<ConventionViolation> found) {
    final Path file = changesetsDir.resolve(fileName);
    final List<?> entries;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      final Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
      entries = YamlNodes.list(YamlNodes.map(root).get("databaseChangeLog"));
    } catch (final IOException e) {
      throw new UncheckedIOException("changesetを読めない: " + file, e);
    }
    ChangesetStructureRules.oneChangesetPerFile(fileName, entries).ifPresent(found::add);
    return entries.stream()
        .map(entry -> YamlNodes.map(entry).get("changeSet"))
        .filter(node -> node != null)
        .findFirst()
        .map(node -> new Changeset(fileName, YamlNodes.map(node)));
  }
}
