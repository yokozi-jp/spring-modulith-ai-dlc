package com.example.demo.jooq.tables;

import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.SQLDataType;

/** 更新とデータパッチのカラムを省いた、追記だけのワークテーブルを模したテスト用のテーブル。 */
public final class FixtureWorkLogTable extends FixtureTable {

  private static final long serialVersionUID = 1L;

  /** テーブルのインスタンス。 */
  public static final FixtureWorkLogTable FIXTURE_WORK_LOG = new FixtureWorkLogTable();

  /** 主キー。 */
  public final TableField<Record, Long> LOG_ID = notNull("log_id", SQLDataType.BIGINT);

  private FixtureWorkLogTable() {
    super("w_fixture_log");
  }
}
