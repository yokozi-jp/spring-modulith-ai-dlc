package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.FixtureTablesExtension;
import java.time.Instant;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link TestCommonColumns} で、Spring
 * のテストから共通カラムを登録できることを確かめる（docs/backend/class-roles/repository.md の例と同じ呼び方）。各テストはロールバックする。
 */
@DatabaseTest
@ExtendWith(FixtureTablesExtension.class)
class TestCommonColumnsTest {

  /** 共通処理に固定する時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-03T00:00:00.123456Z");

  /** アプリロールで接続した jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("runAs の中では、固定の時刻、trace ID、pgm_cd で共通カラムを登録できる")
  void insertsCommonColumnsInsideRunAs() {
    final CommonColumns commonColumns = TestCommonColumns.at(NOW);

    TestCommonColumns.runAs(
        () ->
            dsl.insertInto(FIXTURE_ITEM)
                .set(commonColumns.forInsert(FIXTURE_ITEM))
                .set(FIXTURE_ITEM.ITEM_ID, 1L)
                .set(FIXTURE_ITEM.ITEM_NAME, "item")
                .execute());

    final Record row =
        dsl.selectFrom(FIXTURE_ITEM).where(FIXTURE_ITEM.ITEM_ID.eq(1L)).fetchSingle();
    assertThat(row.get(FIXTURE_ITEM.CREATED_AT)).as("created_at").isEqualTo(NOW);
    assertThat(row.get(FIXTURE_ITEM.CREATED_BY))
        .as("created_by")
        .isEqualTo(TestCommonColumns.PGM_CD);
    assertThat(row.get(FIXTURE_ITEM.CREATED_PGM_CD))
        .as("created_pgm_cd")
        .isEqualTo(TestCommonColumns.PGM_CD);
    assertThat(row.get(FIXTURE_ITEM.CREATED_TX_ID))
        .as("created_tx_id")
        .isEqualTo(TestCommonColumns.TRACE_ID);
  }

  @Test
  @DisplayName("runAs の外では pgm_cd が束縛されず、共通カラムを組み立てられない")
  void rejectsOutsideRunAs() {
    final CommonColumns commonColumns = TestCommonColumns.at(NOW);

    assertThatThrownBy(() -> commonColumns.forInsert(FIXTURE_ITEM))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pgm_cd is not bound");
  }
}
