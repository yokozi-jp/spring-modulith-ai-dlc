package com.example.demo.shared.persistence;

import static com.example.demo.jooq.tables.FixtureItemDetailTable.FIXTURE_ITEM_DETAIL;
import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;

import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;

/**
 * {@link TableWriter} のテストが、テスト専用のテーブルに {@code lock_no = 1} の行を作る。共通カラムは {@code seedColumns}
 * の時刻とトレースで書く。
 */
/* package */ final class FixtureSeeds {

  /** 行を作るときに束縛する pgm_cd。 */
  /* package */ static final String SEED_PGM_CD = "shared.Seed";

  /** 行を作る jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 行を作るときの共通カラムの値。 */
  private final CommonColumns seedColumns;

  /* package */ FixtureSeeds(final DSLContext dsl, final CommonColumns seedColumns) {
    this.dsl = dsl;
    this.seedColumns = seedColumns;
  }

  /** {@code lock_no = 1} の、item_code のないルートの行を作る。 */
  /* package */ void insertItem(final long itemId, final String name) {
    insertItem(itemId, name, null);
  }

  /** {@code lock_no = 1} のルートの行を作る。 */
  /* package */ void insertItem(final long itemId, final String name, final @Nullable String code) {
    ScopedValue.where(PgmCdAspect.PGM_CD, SEED_PGM_CD)
        .run(() ->
            dsl.insertInto(FIXTURE_ITEM)
                .set(seedColumns.forInsert(FIXTURE_ITEM))
                .set(FIXTURE_ITEM.ITEM_ID, itemId)
                .set(FIXTURE_ITEM.ITEM_NAME, name)
                .set(FIXTURE_ITEM.ITEM_CODE, code)
                .execute());
  }

  /** {@code lock_no = 1} の子の行を作る。 */
  /* package */ void insertDetail(final long itemId, final int detailNo, final String text) {
    ScopedValue.where(PgmCdAspect.PGM_CD, SEED_PGM_CD)
        .run(() ->
            dsl.insertInto(FIXTURE_ITEM_DETAIL)
                .set(seedColumns.forInsert(FIXTURE_ITEM_DETAIL))
                .set(FIXTURE_ITEM_DETAIL.ITEM_ID, itemId)
                .set(FIXTURE_ITEM_DETAIL.DETAIL_NO, detailNo)
                .set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, text)
                .execute());
  }
}
