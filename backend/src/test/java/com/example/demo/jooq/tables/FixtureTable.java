package com.example.demo.jooq.tables;

import java.time.Instant;
import org.jooq.DataType;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

/**
 * 生成クラスを模したテスト用のテーブルの共通部分。
 *
 * <p>fixture スキーマに置き、ワークテーブルを含むすべてのテーブルが持つ作成のカラムと {@code lock_no} を定義する。
 */
abstract class FixtureTable extends TableImpl<Record> {

  private static final long serialVersionUID = 1L;

  /** 作成日時。 */
  public final TableField<Record, Instant> CREATED_AT = notNull("created_at", SQLDataType.INSTANT);

  /** 作成者。 */
  public final TableField<Record, String> CREATED_BY =
      notNull("created_by", SQLDataType.VARCHAR(64));

  /** 作成した機能。 */
  public final TableField<Record, String> CREATED_PGM_CD =
      notNull("created_pgm_cd", SQLDataType.VARCHAR(64));

  /** 作成した処理の trace ID。 */
  public final TableField<Record, String> CREATED_TX_ID =
      notNull("created_tx_id", SQLDataType.VARCHAR(32));

  /** ロック番号。 */
  public final TableField<Record, Long> LOCK_NO = notNull("lock_no", SQLDataType.BIGINT);

  /* package */ FixtureTable(final String name) {
    super(DSL.name(name), DSL.schema(DSL.name("fixture")));
  }

  /** NULL を許さないカラムを作る。 */
  /* package */ final <T> TableField<Record, T> notNull(final String name, final DataType<T> type) {
    return nullable(name, type.nullable(false));
  }

  /** NULL を許すカラムを作る。 */
  /* package */ final <T> TableField<Record, T> nullable(
      final String name, final DataType<T> type) {
    return createField(DSL.name(name), type, this, "");
  }
}
