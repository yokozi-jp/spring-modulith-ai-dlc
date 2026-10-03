package com.example.demo.jooq.tables;

import java.time.Instant;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

/** 更新とデータパッチのカラムを省いた、追記だけのワークテーブルを模したテスト用のテーブル。 */
public final class FixtureWorkLogTable extends TableImpl<Record> {

  private static final long serialVersionUID = 1L;

  /** テーブルのインスタンス。 */
  public static final FixtureWorkLogTable FIXTURE_WORK_LOG = new FixtureWorkLogTable();

  /** 主キー。 */
  public final TableField<Record, Long> LOG_ID =
      createField(DSL.name("log_id"), SQLDataType.BIGINT.nullable(false), this, "");

  /** 作成日時。 */
  public final TableField<Record, Instant> CREATED_AT =
      createField(DSL.name("created_at"), SQLDataType.INSTANT.nullable(false), this, "");

  /** 作成者。 */
  public final TableField<Record, String> CREATED_BY =
      createField(DSL.name("created_by"), SQLDataType.VARCHAR(64).nullable(false), this, "");

  /** 作成した機能。 */
  public final TableField<Record, String> CREATED_PGM_CD =
      createField(DSL.name("created_pgm_cd"), SQLDataType.VARCHAR(64).nullable(false), this, "");

  /** 作成した処理の trace ID。 */
  public final TableField<Record, String> CREATED_TX_ID =
      createField(DSL.name("created_tx_id"), SQLDataType.VARCHAR(32).nullable(false), this, "");

  /** ロック番号。 */
  public final TableField<Record, Long> LOCK_NO =
      createField(DSL.name("lock_no"), SQLDataType.BIGINT.nullable(false), this, "");

  private FixtureWorkLogTable() {
    super(DSL.name("w_fixture_log"), DSL.schema(DSL.name("fixture")));
  }
}
