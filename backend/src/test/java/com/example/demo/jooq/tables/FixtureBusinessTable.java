package com.example.demo.jooq.tables;

import java.time.Instant;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.SQLDataType;

/** 業務テーブルの生成クラスを模したテスト用のテーブルの共通部分。更新とデータパッチのカラムを定義する。 */
abstract class FixtureBusinessTable extends FixtureTable {

  private static final long serialVersionUID = 1L;

  /** 更新日時。 */
  public final TableField<Record, Instant> UPDATED_AT = notNull("updated_at", SQLDataType.INSTANT);

  /** 更新者。 */
  public final TableField<Record, String> UPDATED_BY =
      notNull("updated_by", SQLDataType.VARCHAR(64));

  /** 更新した機能。 */
  public final TableField<Record, String> UPDATED_PGM_CD =
      notNull("updated_pgm_cd", SQLDataType.VARCHAR(64));

  /** 更新した処理の trace ID。 */
  public final TableField<Record, String> UPDATED_TX_ID =
      notNull("updated_tx_id", SQLDataType.VARCHAR(32));

  /** データパッチの日時。 */
  public final TableField<Record, Instant> PATCHED_AT = nullable("patched_at", SQLDataType.INSTANT);

  /** データパッチの作業者。 */
  public final TableField<Record, String> PATCHED_BY =
      nullable("patched_by", SQLDataType.VARCHAR(64));

  /** データパッチの課題番号。 */
  public final TableField<Record, Integer> PATCHED_ID = nullable("patched_id", SQLDataType.INTEGER);

  /* package */ FixtureBusinessTable(final String name) {
    super(name);
  }
}
