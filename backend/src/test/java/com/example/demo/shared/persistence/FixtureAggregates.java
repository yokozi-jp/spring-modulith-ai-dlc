package com.example.demo.shared.persistence;

import static com.example.demo.jooq.tables.FixtureItemDetailTable.FIXTURE_ITEM_DETAIL;
import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;

import com.example.demo.shared.concurrency.Versioned;
import java.util.List;
import org.jooq.Record;

/** {@link TableWriter} のテストが使う、テスト専用のテーブルの集約の宣言。 */
final class FixtureAggregates {

  /** 子の行。親の中の行は、明細番号で同定する。 */
  /* package */ static final ChildTable<Record> DETAILS =
      ChildTable.of(
          FIXTURE_ITEM_DETAIL, FIXTURE_ITEM_DETAIL.ITEM_ID, FIXTURE_ITEM_DETAIL.DETAIL_NO);

  /** 子を持つ集約。ルートの一意の列と内部の主キーは、同じ item_id にする。 */
  /* package */ static final AggregateTable<Record, Long, Item> ITEM =
      AggregateTable.of(
          FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID, Item::id, FIXTURE_ITEM.ITEM_ID, DETAILS);

  /** 子を持たない集約。 */
  /* package */ static final AggregateTable<Record, Long, Item> ITEM_ONLY =
      AggregateTable.of(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID, Item::id, FIXTURE_ITEM.ITEM_ID);

  /** 子を持ち、item_code の重複を許す集約。item_id の重複は許さない。 */
  /* package */ static final AggregateTable<Record, Long, Item> ITEM_IDEMPOTENT_ON_CODE =
      AggregateTable.ofIdempotentOn(
          FIXTURE_ITEM,
          FIXTURE_ITEM.ITEM_ID,
          Item::id,
          FIXTURE_ITEM.ITEM_ID,
          List.of(FIXTURE_ITEM.ITEM_CODE),
          DETAILS);

  private FixtureAggregates() {}

  /** テスト用の集約ルート。ルートの一意の列は {@code id}、読み込んだ版は {@code lockNo} である。 */
  /* package */ record Item(long id, String name, long lockNo) implements Versioned {}

  /** 子の行にする値。 */
  /* package */ record Detail(int number, String text) {}

  /* package */ static Detail detail(final int number, final String text) {
    return new Detail(number, text);
  }

  /** 明細番号と文言を子の列に書く、{@link #DETAILS} の全行を作る。 */
  /* package */ static ChildRows<Record> detailRows(final List<Detail> details) {
    return DETAILS.rows(
        details,
        (detail, set) ->
            set.set(FIXTURE_ITEM_DETAIL.DETAIL_NO, detail.number())
                .set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, detail.text()));
  }

  /** 版が {@code lockNo} で、名前が {@code x} の集約ルートを表す。 */
  /* package */ static Item itemRoot(final long id, final long lockNo) {
    return new Item(id, "x", lockNo);
  }
}
