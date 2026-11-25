package com.example.demo.jooq.tables;

import java.time.Instant;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.SQLDataType;

/** {@link FixtureItemDetailTable} の削除前の行を残す、テスト用の履歴の表。主キーも一意制約も持たない。 */
public final class FixtureItemDetailArchiveTable extends FixtureBusinessTable {

  private static final long serialVersionUID = 1L;

  /** テーブルのインスタンス。 */
  public static final FixtureItemDetailArchiveTable FIXTURE_ITEM_DETAIL_ARCHIVE =
      new FixtureItemDetailArchiveTable();

  /** 親の主キー。 */
  public final TableField<Record, Long> ITEM_ID = notNull("item_id", SQLDataType.BIGINT);

  /** 親の中の明細の番号。 */
  public final TableField<Record, Integer> DETAIL_NO = notNull("detail_no", SQLDataType.INTEGER);

  /** 明細の本文。 */
  public final TableField<Record, String> DETAIL_TEXT =
      notNull("detail_text", SQLDataType.VARCHAR(50));

  /** 退避日時。 */
  public final TableField<Record, Instant> ARCHIVED_AT =
      notNull("archived_at", SQLDataType.INSTANT);

  /** 退避者。 */
  public final TableField<Record, String> ARCHIVED_BY =
      notNull("archived_by", SQLDataType.VARCHAR(64));

  /** 退避した機能。 */
  public final TableField<Record, String> ARCHIVED_PGM_CD =
      notNull("archived_pgm_cd", SQLDataType.VARCHAR(64));

  /** 退避した処理の trace ID。 */
  public final TableField<Record, String> ARCHIVED_TX_ID =
      notNull("archived_tx_id", SQLDataType.VARCHAR(32));

  private FixtureItemDetailArchiveTable() {
    super("h_fixture_item_detail");
  }
}
