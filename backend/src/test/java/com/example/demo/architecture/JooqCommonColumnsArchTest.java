package com.example.demo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.DemoApplication;
import com.example.demo.jooq.tables.FixtureItemTable;
import com.example.demo.shared.infrastructure.persistence.AllowedCommonColumnAccess;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生成クラスの共通カラムを参照できる場所を、shared の共通処理だけに限る（ADR-048、docs/database/postgresql-common-columns.md）。
 *
 * <p>INSERT の共通カラムは {@code CommonColumns.forInsert} で、UPDATE と DELETE は {@code TableWriter}
 * で書く。{@code LOCK_NO} は各モジュールが楽観的ロックの版を読むため対象外にし、書き込みは {@code ColumnValues} が拒否する。生成した Record の
 * getter は対象にしない。
 */
@AnalyzeClasses(packagesOf = DemoApplication.class, importOptions = ProductionCodeOnly.class)
class JooqCommonColumnsArchTest {

  /** 共通カラムを参照してよいパッケージ。 */
  private static final String SHARED_PERSISTENCE =
      "com.example.demo.shared.infrastructure.persistence..";

  /** 生成クラスの {@code CREATED_*}、{@code UPDATED_*}、{@code PATCHED_*} は shared の共通処理だけが参照する。 */
  @ArchTest
  /* package */ static final ArchRule commonColumnsAreReferencedOnlyBySharedPersistence =
      commonColumnsRule();

  @Test
  @DisplayName("shared の外から生成クラスの共通カラムを参照すると拒否し、LOCK_NO と shared からの参照は許す")
  void commonColumnReferencesOutsideSharedAreRejected() {
    // フィクスチャを実際に呼び出してIDEにも使用済みと認識させる。拒否判定は続くArchUnitの検査が担う。
    assertThat(CommonColumnFieldBypass.readCommonColumns()).hasSize(4);
    assertThat(AllowedCommonColumnAccess.createdAt()).isNotNull();

    assertThatThrownBy(
            () ->
                commonColumnsRule()
                    .check(new ClassFileImporter().importClasses(CommonColumnFieldBypass.class)))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("CREATED_AT")
        .hasMessageContaining("UPDATED_BY")
        .hasMessageContaining("PATCHED_AT")
        .hasMessageContaining("was violated (3 times)")
        .hasMessageNotContaining("FixtureItemTable.LOCK_NO");
    assertThatCode(
            () ->
                commonColumnsRule()
                    .check(
                        // that() が空にならないよう、共通カラムを参照しない shared の外のクラスも渡す。
                        new ClassFileImporter()
                            .importClasses(AllowedCommonColumnAccess.class, DemoApplication.class)))
        .as("shared の共通処理からの参照")
        .doesNotThrowAnyException();
  }

  private static ArchRule commonColumnsRule() {
    return noClasses()
        .that()
        .resideOutsideOfPackage(SHARED_PERSISTENCE)
        .should()
        .accessFieldWhere(
            DescribedPredicate.describe(
                "生成クラスの CREATED_*、UPDATED_*、PATCHED_* フィールド",
                access ->
                    JavaClass.Predicates.resideInAPackage("com.example.demo.jooq..")
                            .test(access.getTargetOwner())
                        && access
                            .getTarget()
                            .getName()
                            .matches("(CREATED|UPDATED|PATCHED)_[A-Z0-9_]+")))
        .because(
            "共通カラムの値の作り方をモジュールごとに食い違わせず、業務ロジックと画面で共通カラムを参照しないため。"
                + "LOCK_NO は楽観的ロックの版を読むため対象外にし、書き込みは ColumnValues が拒否する。"
                + "直し方：INSERT の共通カラムは shared の CommonColumns.forInsert で、UPDATE と DELETE は TableWriter で書き、"
                + "生成クラスの CREATED_*、UPDATED_*、PATCHED_* を直接参照しない。"
                + "規約：docs/database/postgresql-common-columns.md、"
                + "docs/adr/ADR-048-add-shared-module-for-jooq-common-code.md");
  }

  /** ArchUnit の拒否経路を検証するため、shared の外から共通カラムを参照するフィクスチャ。 */
  private static final class CommonColumnFieldBypass {

    private static List<Object> readCommonColumns() {
      return List.of(
          FixtureItemTable.FIXTURE_ITEM.CREATED_AT,
          FixtureItemTable.FIXTURE_ITEM.UPDATED_BY,
          FixtureItemTable.FIXTURE_ITEM.PATCHED_AT,
          FixtureItemTable.FIXTURE_ITEM.LOCK_NO);
    }
  }
}
