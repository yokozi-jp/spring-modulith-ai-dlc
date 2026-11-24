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

  /** 任意のSQLの規則の記号。 */
  private static final String ARBITRARY_SQL = "M17";

  /**
   * 本番のchangesetの許可リスト。
   *
   * <p>各モジュールのchangesetは、自スキーマの作成とrollbackのスキーマの削除を任意のSQLで書く。権限のSQLは許可リストに載せず、{@link
   * ChangesetPrivilegeRules}（G3）が判定する。
   */
  /* package */ static final List<AllowlistEntry> ALLOWLIST =
      Stream.of(
              moduleSchemaSql("001-create-event-publication-tables", "modulith"),
              moduleSchemaSql("003-create-product-tables", "product"),
              moduleSchemaSql("004-create-order-tables", "ordering"),
              moduleSchemaSql("006-create-payment-tables", "payment"),
              moduleSchemaSql("014-create-tenancy-tables", "tenancy"),
              List.of(
                  new AllowlistEntry(
                      ARBITRARY_SQL,
                      "027-add-event-publication-archive-abandoned-index: CREATE INDEX CONCURRENTLY"
                          + " event_publication_archive_abandoned_idx ON"
                          + " modulith.event_publication_archive (publication_date) WHERE status ="
                          + " 'ABANDONED';",
                      "Liquibase OSS 5.0.4のcreateIndexは部分索引の条件とCONCURRENTLYを書けない。"
                          + "稼働中のarchiveの表へ書き込みを止めずに作る（ADR-079）。"),
                  new AllowlistEntry(
                      ARBITRARY_SQL,
                      "027-add-event-publication-archive-abandoned-index: DROP INDEX CONCURRENTLY"
                          + " IF EXISTS modulith.event_publication_archive_abandoned_idx;",
                      "中断したCREATE INDEX CONCURRENTLYが残す無効な索引を再試行の前に消すSQLと、そのrollbackである。"
                          + "dropIndexはCONCURRENTLYを書けない。")))
          .flatMap(List::stream)
          .toList();

  private ChangesetLint() {
    // 静的メソッドだけを持つ。
  }

  /** モジュールのスキーマの作成と削除の任意のSQL（M17）を許可リストに載せる。 */
  private static List<AllowlistEntry> moduleSchemaSql(final String changeset, final String schema) {
    return List.of(
        new AllowlistEntry(
            ARBITRARY_SQL,
            changeset + ": CREATE SCHEMA " + schema + " AUTHORIZATION CURRENT_USER;",
            "Liquibase OSS 5.0.4にはスキーマを作るChange Typeがない。"
                + "ADR-011はモジュールのスキーマをchangesetで作ると定めている。"),
        new AllowlistEntry(
            ARBITRARY_SQL,
            changeset + ": DROP SCHEMA " + schema + ";",
            "CREATE SCHEMAのrollbackであり、Liquibase OSSにはスキーマを削除するChange Typeがない。"));
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
    found.addAll(ChangesetForeignKeyRules.check(changesets));
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
