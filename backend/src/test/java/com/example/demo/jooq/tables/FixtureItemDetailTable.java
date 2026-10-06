package com.example.demo.jooq.tables;

import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.SQLDataType;

/** 集約の子のテーブルを模したテスト用のテーブル。主キーは親の {@code item_id} と {@code detail_no}。 */
public final class FixtureItemDetailTable extends FixtureBusinessTable {

  private static final long serialVersionUID = 1L;

  /** テーブルのインスタンス。 */
  public static final FixtureItemDetailTable FIXTURE_ITEM_DETAIL = new FixtureItemDetailTable();

  /** 親の主キー。 */
  public final TableField<Record, Long> ITEM_ID = notNull("item_id", SQLDataType.BIGINT);

  /** 親の中の明細の番号。 */
  public final TableField<Record, Integer> DETAIL_NO = notNull("detail_no", SQLDataType.INTEGER);

  /** 明細の本文。 */
  public final TableField<Record, String> DETAIL_TEXT =
      notNull("detail_text", SQLDataType.VARCHAR(50));

  private FixtureItemDetailTable() {
    super("t_fixture_item_detail");
  }
}
