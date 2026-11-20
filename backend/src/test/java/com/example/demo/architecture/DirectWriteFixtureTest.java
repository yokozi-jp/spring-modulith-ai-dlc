package com.example.demo.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import archfixture.violating.ordering.infrastructure.persistence.DirectOrderWriter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jooq.ConnectionProvider;
import org.jooq.DAO;
import org.jooq.DSLContext;
import org.jooq.Delete;
import org.jooq.InsertOnDuplicateStep;
import org.jooq.InsertQuery;
import org.jooq.LoaderOptionsStep;
import org.jooq.Merge;
import org.jooq.Query;
import org.jooq.UpdatableRecord;
import org.jooq.Update;
import org.jooq.UpdateSetMoreStep;
import org.jooq.WithStep;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.object.SqlUpdate;

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

  /** 違反フィクスチャの import 結果。 */
  private static final JavaClasses VIOLATING_CLASSES =
      new ClassFileImporter().importPackages(VIOLATING);

  /** 禁止の呼び出しの名前 update。 */
  private static final String UPDATE = "update";

  /** 禁止の呼び出しの名前 delete。 */
  private static final String DELETE = "delete";

  /** 禁止の呼び出しの名前 onDuplicateKeyUpdate。 */
  private static final String ON_DUPLICATE_KEY_UPDATE = "onDuplicateKeyUpdate";

  /** 禁止の呼び出しの名前 execute。 */
  private static final String EXECUTE = "execute";

  /** TableWriter の H1 で確かめる、違反フィクスチャのメソッドの行。 */
  private static final List<DirectWrite> DIRECT_WRITES =
      List.of(
          new DirectWrite("dslUpdate", DSLContext.class, UPDATE),
          new DirectWrite("dslDelete", DSLContext.class, DELETE),
          new DirectWrite("dslDeleteFrom", DSLContext.class, "deleteFrom"),
          new DirectWrite("dslMergeInto", DSLContext.class, "mergeInto"),
          new DirectWrite("dslBatchUpdate", DSLContext.class, "batchUpdate"),
          new DirectWrite("dslBatchStore", DSLContext.class, "batchStore"),
          new DirectWrite("dslBatchDelete", DSLContext.class, "batchDelete"),
          new DirectWrite("dslBatchMerge", DSLContext.class, "batchMerge"),
          new DirectWrite("dslExecuteUpdate", DSLContext.class, "executeUpdate"),
          new DirectWrite("dslExecuteDelete", DSLContext.class, "executeDelete"),
          new DirectWrite("dslConnection", DSLContext.class, "connection"),
          new DirectWrite("dslConnectionResult", DSLContext.class, "connectionResult"),
          new DirectWrite("dslUpdateQuery", DSLContext.class, "updateQuery"),
          new DirectWrite("dslDeleteQuery", DSLContext.class, "deleteQuery"),
          new DirectWrite("withUpdate", WithStep.class, UPDATE),
          new DirectWrite(
              "insertQueryOnDuplicateKeyUpdate", InsertQuery.class, ON_DUPLICATE_KEY_UPDATE),
          new DirectWrite("insertQueryAddValueForUpdate", InsertQuery.class, "addValueForUpdate"),
          new DirectWrite("insertQueryAddValuesForUpdate", InsertQuery.class, "addValuesForUpdate"),
          new DirectWrite("insertQueryOnConflictWhere", InsertQuery.class, "onConflictWhere"),
          new DirectWrite(
              "loaderOnDuplicateKeyUpdate", LoaderOptionsStep.class, ON_DUPLICATE_KEY_UPDATE),
          new DirectWrite("qomOnDuplicateKeyUpdate", QOM.Insert.class, "$onDuplicateKeyUpdate"),
          new DirectWrite("springScriptPopulator", ResourceDatabasePopulator.class, EXECUTE),
          new DirectWrite(
              "bootScriptInitializer", DataSourceScriptDatabaseInitializer.class, "<init>"),
          new DirectWrite("springSqlUpdate", SqlUpdate.class, UPDATE),
          new DirectWrite("staticDslUpdate", DSL.class, UPDATE),
          new DirectWrite("lambdaExecute", UpdateSetMoreStep.class, EXECUTE),
          new DirectWrite("updateVariableExecute", Update.class, EXECUTE),
          new DirectWrite("updateMethodReference", Query.class, EXECUTE),
          new DirectWrite("updateReturning", UpdateSetMoreStep.class, "returning"),
          new DirectWrite("updateReturningResult", UpdateSetMoreStep.class, "returningResult"),
          new DirectWrite("updateExecuteAsync", Update.class, "executeAsync"),
          new DirectWrite("deleteExecute", Delete.class, EXECUTE),
          new DirectWrite("mergeExecute", Merge.class, EXECUTE),
          new DirectWrite("upsertOnConflict", InsertOnDuplicateStep.class, "onConflict"),
          new DirectWrite(
              "upsertOnConflictOnConstraint",
              InsertOnDuplicateStep.class,
              "onConflictOnConstraint"),
          new DirectWrite(
              "upsertOnDuplicateKeyUpdate", InsertOnDuplicateStep.class, ON_DUPLICATE_KEY_UPDATE),
          new DirectWrite("recordStore", UpdatableRecord.class, "store"),
          new DirectWrite("recordUpdate", UpdatableRecord.class, UPDATE),
          new DirectWrite("recordDelete", UpdatableRecord.class, DELETE),
          new DirectWrite("recordMerge", UpdatableRecord.class, "merge"),
          new DirectWrite("daoUpdate", DAO.class, UPDATE),
          new DirectWrite("daoDelete", DAO.class, DELETE),
          new DirectWrite("daoDeleteById", DAO.class, "deleteById"),
          new DirectWrite("daoMerge", DAO.class, "merge"),
          new DirectWrite("jdbcTemplate", JdbcTemplate.class, UPDATE),
          new DirectWrite("dataSourceConnection", DataSource.class, "getConnection"),
          new DirectWrite("jdbcConnection", Connection.class, "prepareStatement"),
          new DirectWrite("jdbcStatement", Statement.class, "executeUpdate"),
          new DirectWrite("connectionProvider", ConnectionProvider.class, "acquire"));

  /** 規則が違反として報告した行。 */
  private static final List<String> VIOLATION_LINES =
      Objects.requireNonNull(
              catchThrowable(() ->
                  TableWriterArchTest.tableWritesGoThroughTableWriterRule(VIOLATING)
                      .check(VIOLATING_CLASSES)),
              "tableWritesGoThroughTableWriter が違反フィクスチャを検出しない")
          .getMessage()
          .lines()
          .toList();

  @ParameterizedTest(name = "{0}")
  @MethodSource("directWrites")
  @DisplayName("tableWritesGoThroughTableWriter は DirectOrderWriter の各メソッドを、意図した禁止の呼び出しで検出する")
  void tableWritesRuleDetectsEachDirectWriteThroughItsForbiddenCall(
      final String method, final DirectWrite write) {
    assertThat(VIOLATION_LINES)
        .as(method)
        .anyMatch(line ->
            line.contains(DirectOrderWriter.class.getName() + "." + method + "(")
                && line.contains("<" + write.owner().getName() + "." + write.name() + "("));
  }

  private static Stream<Arguments> directWrites() {
    return DIRECT_WRITES.stream().map(write -> Arguments.of(write.method(), write));
  }

  @Test
  @DisplayName("DirectOrderWriter の公開メソッドは、すべて DIRECT_WRITES に載っている")
  void everyDirectOrderWriterMethodIsListed() {
    final Set<String> methods =
        Arrays.stream(DirectOrderWriter.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toSet());

    assertThat(DIRECT_WRITES.stream().map(DirectWrite::method))
        .as("DirectOrderWriter に足したメソッドは、DIRECT_WRITES に行を足す")
        .containsExactlyInAnyOrderElementsOf(methods);
  }

  @Test
  @DisplayName("禁止する名前は、すべていずれかの DirectOrderWriter のメソッドの禁止の呼び出しになっている")
  void everyForbiddenNameHasADirectWriteRow() {
    // 同じ名前の集合を共有する持ち主（DSLContext、DSL、WithStep など）は、集合の各名前につき、どれか一つの行があればよい。
    final Map<Set<String>, Set<Class<?>>> ownersByNames =
        TableWriterArchTest.NAMED_WRITES.entrySet().stream()
            .collect(
                Collectors.groupingBy(
                    Map.Entry::getValue,
                    Collectors.mapping(Map.Entry::getKey, Collectors.toSet())));
    final List<String> missing = new ArrayList<>();
    ownersByNames.forEach((names, owners) -> addMissing(missing, names, owners));
    addMissing(missing, TableWriterArchTest.QUERY_EXECUTIONS, Set.of(Update.class, Delete.class));

    assertThat(missing)
        .as("禁止する名前を足したら、その呼び出しを持つメソッドを DirectOrderWriter と DIRECT_WRITES に足す")
        .isEmpty();
  }

  /** 名前ごとに、持ち主のどれかに代入できる型の呼び出しを持つ行がなければ、{@code missing} に足す。 */
  private static void addMissing(
      final List<String> missing, final Set<String> names, final Set<Class<?>> owners) {
    for (final String name : names) {
      final boolean covered =
          DIRECT_WRITES.stream()
              .anyMatch(write ->
                  write.name().equals(name)
                      && owners.stream().anyMatch(owner -> owner.isAssignableFrom(write.owner())));
      if (!covered) {
        missing.add(owners.stream().map(Class::getSimpleName).sorted().toList() + "." + name);
      }
    }
  }

  /** 違反フィクスチャのメソッドと、そのメソッドが検出される禁止の呼び出し。 */
  private record DirectWrite(String method, Class<?> owner, String name) {}
}
