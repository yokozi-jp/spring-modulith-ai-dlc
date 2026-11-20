package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemDetailTable.FIXTURE_ITEM_DETAIL;
import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;

import com.example.demo.shared.concurrency.Versioned;
import org.jooq.Record;

/** {@link TableWriter} のテストが使う、テスト専用のテーブルの集約の宣言。 */
final class FixtureAggregates {

  /** 子の行。親の中の行は、明細番号で同定する。 */
  /* package */ static final ChildTable<Record, Record> DETAILS =
      ChildTable.of(
          FIXTURE_ITEM_DETAIL, FIXTURE_ITEM_DETAIL.ITEM_ID, FIXTURE_ITEM_DETAIL.DETAIL_NO);

  /** 子を持つ集約。ルートの一意の列と内部の主キーは、同じ item_id にする。 */
  /* package */ static final AggregateTable<Record, Long> ITEM =
      AggregateTable.of(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID, FIXTURE_ITEM.ITEM_ID, DETAILS);

  /** 子を持たない集約。 */
  /* package */ static final AggregateTable<Record, Long> ITEM_ONLY =
      AggregateTable.of(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID, FIXTURE_ITEM.ITEM_ID);

  private FixtureAggregates() {}

  /** 読み込んだ版が {@code lockNo} の集約ルートを表す。 */
  /* package */ static Versioned version(final long lockNo) {
    return () -> lockNo;
  }
}
