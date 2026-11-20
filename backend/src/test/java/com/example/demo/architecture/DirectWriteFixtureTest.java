package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code tableWritesGoThroughTableWriter} が、違反フィクスチャ {@code DirectOrderWriter}
 * の各メソッドを、意図した禁止の呼び出しで検出することを確かめる。
 *
 * <p>行ごとに検出される呼び出しを確かめるため、メソッドが禁止の呼び出しを二つ持つと、片方の禁止を外しても行が通る状態を防ぐ。
 */
// JavaClasses は ArchUnit の import 結果を表す公開 API 型であり、インタフェースへ置き換えられない。
@SuppressWarnings("PMD.LooseCoupling")
class DirectWriteFixtureTest {

  /** 違反フィクスチャの基底パッケージ。 */
  private static final String VIOLATING = "archfixture.violating";

  /** 違反フィクスチャのクラス名の接頭辞。 */
  private static final String VIOLATING_PREFIX = VIOLATING + ".";

  /** TableWriter を通さずに書く違反フィクスチャのクラス名。 */
  private static final String DIRECT_WRITER =
      "ordering.infrastructure.persistence.DirectOrderWriter";

  /** 違反フィクスチャの import 結果。 */
  private static final JavaClasses VIOLATING_CLASSES =
      new ClassFileImporter().importPackages(VIOLATING);

  /** TableWriter の H1 で、違反フィクスチャのメソッドと、そのメソッドが検出される禁止の呼び出し（完全修飾名の前半）。 */
  private static final Map<String, String> DIRECT_WRITES =
      Map.ofEntries(
          Map.entry("dslUpdate", "org.jooq.DSLContext.update("),
          Map.entry("dslDelete", "org.jooq.DSLContext.delete("),
          Map.entry("dslDeleteFrom", "org.jooq.DSLContext.deleteFrom("),
          Map.entry("dslMergeInto", "org.jooq.DSLContext.mergeInto("),
          Map.entry("dslBatchUpdate", "org.jooq.DSLContext.batchUpdate("),
          Map.entry("dslBatchStore", "org.jooq.DSLContext.batchStore("),
          Map.entry("dslBatchDelete", "org.jooq.DSLContext.batchDelete("),
          Map.entry("dslBatchMerge", "org.jooq.DSLContext.batchMerge("),
          Map.entry("dslExecuteUpdate", "org.jooq.DSLContext.executeUpdate("),
          Map.entry("dslExecuteDelete", "org.jooq.DSLContext.executeDelete("),
          Map.entry("dslConnection", "org.jooq.DSLContext.connection("),
          Map.entry("dslConnectionResult", "org.jooq.DSLContext.connectionResult("),
          Map.entry("dslUpdateQuery", "org.jooq.DSLContext.updateQuery("),
          Map.entry("dslDeleteQuery", "org.jooq.DSLContext.deleteQuery("),
          Map.entry("withUpdate", "org.jooq.WithStep.update("),
          Map.entry(
              "insertQueryOnDuplicateKeyUpdate", "org.jooq.InsertQuery.onDuplicateKeyUpdate("),
          Map.entry("insertQueryAddValueForUpdate", "org.jooq.InsertQuery.addValueForUpdate("),
          Map.entry("insertQueryAddValuesForUpdate", "org.jooq.InsertQuery.addValuesForUpdate("),
          Map.entry("insertQueryOnConflictWhere", "org.jooq.InsertQuery.onConflictWhere("),
          Map.entry(
              "loaderOnDuplicateKeyUpdate", "org.jooq.LoaderOptionsStep.onDuplicateKeyUpdate("),
          Map.entry("qomOnDuplicateKeyUpdate", "org.jooq.impl.QOM$Insert.$onDuplicateKeyUpdate("),
          Map.entry(
              "springScriptPopulator",
              "org.springframework.jdbc.datasource.init.ResourceDatabasePopulator.execute("),
          Map.entry(
              "bootScriptInitializer",
              "org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer.<init>("),
          Map.entry("springSqlUpdate", "org.springframework.jdbc.object.SqlUpdate.update("),
          Map.entry("staticDslUpdate", "org.jooq.impl.DSL.update("),
          Map.entry("lambdaExecute", "org.jooq.UpdateSetMoreStep.execute("),
          Map.entry("updateVariableExecute", "org.jooq.Update.execute("),
          Map.entry("updateMethodReference", "org.jooq.Query.execute("),
          Map.entry("updateReturning", "org.jooq.UpdateSetMoreStep.returning("),
          Map.entry("updateReturningResult", "org.jooq.UpdateSetMoreStep.returningResult("),
          Map.entry("updateExecuteAsync", "org.jooq.Update.executeAsync("),
          Map.entry("deleteExecute", "org.jooq.Delete.execute("),
          Map.entry("mergeExecute", "org.jooq.Merge.execute("),
          Map.entry("upsertOnConflict", "org.jooq.InsertOnDuplicateStep.onConflict("),
          Map.entry(
              "upsertOnConflictOnConstraint",
              "org.jooq.InsertOnDuplicateStep.onConflictOnConstraint("),
          Map.entry(
              "upsertOnDuplicateKeyUpdate", "org.jooq.InsertOnDuplicateStep.onDuplicateKeyUpdate("),
          Map.entry("recordStore", "org.jooq.UpdatableRecord.store("),
          Map.entry("recordUpdate", "org.jooq.UpdatableRecord.update("),
          Map.entry("recordDelete", "org.jooq.UpdatableRecord.delete("),
          Map.entry("recordMerge", "org.jooq.UpdatableRecord.merge("),
          Map.entry("daoUpdate", "org.jooq.DAO.update("),
          Map.entry("daoDelete", "org.jooq.DAO.delete("),
          Map.entry("daoDeleteById", "org.jooq.DAO.deleteById("),
          Map.entry("daoMerge", "org.jooq.DAO.merge("),
          Map.entry("jdbcTemplate", "org.springframework.jdbc.core.JdbcTemplate.update("),
          Map.entry("dataSourceConnection", "javax.sql.DataSource.getConnection("),
          Map.entry("jdbcConnection", "java.sql.Connection.prepareStatement("),
          Map.entry("jdbcStatement", "java.sql.Statement.executeUpdate("),
          Map.entry("connectionProvider", "org.jooq.ConnectionProvider.acquire("));

  @ParameterizedTest(name = "{0}")
  @MethodSource("directWrites")
  @DisplayName("tableWritesGoThroughTableWriter は DirectOrderWriter の各メソッドを、意図した禁止の呼び出しで検出する")
  void tableWritesRuleDetectsEachDirectWriteThroughItsForbiddenCall(
      final String method, final String forbiddenCall) {
    final Throwable thrown =
        catchThrowable(() ->
            TableWriterArchTest.tableWritesGoThroughTableWriterRule(VIOLATING)
                .check(VIOLATING_CLASSES));

    assertThat(thrown).as(method).isInstanceOf(AssertionError.class);
    assertThat(thrown.getMessage().lines())
        .as(method)
        .anyMatch(line ->
            line.contains(VIOLATING_PREFIX + DIRECT_WRITER + "." + method + "(")
                && line.contains("<" + forbiddenCall));
  }

  private static Stream<Arguments> directWrites() {
    return DIRECT_WRITES.entrySet().stream()
        .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
  }

  @Test
  @DisplayName("禁止する名前の一覧の値は、すべていずれかの DirectOrderWriter のメソッドの禁止の呼び出しになっている")
  void everyForbiddenNameHasADirectWriteRow() {
    final Set<String> jooqCallNames =
        DIRECT_WRITES.values().stream()
            .filter(call -> call.startsWith("org.jooq."))
            .map(call -> call.substring(call.lastIndexOf('.') + 1, call.length() - 1))
            .collect(Collectors.toSet());
    final Set<String> forbiddenNames = new HashSet<>();
    forbiddenNames.addAll(TableWriterArchTest.DSL_WRITES);
    forbiddenNames.addAll(TableWriterArchTest.QUERY_EXECUTIONS);
    forbiddenNames.addAll(TableWriterArchTest.UPSERTS);
    forbiddenNames.addAll(TableWriterArchTest.RECORD_WRITES);
    forbiddenNames.addAll(TableWriterArchTest.DAO_WRITES);

    assertThat(forbiddenNames).isSubsetOf(jooqCallNames);
  }
}
