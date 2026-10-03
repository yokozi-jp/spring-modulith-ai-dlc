package com.example.demo.jooq.tables;

import com.example.demo.shared.infrastructure.persistence.NullToEmptyStringConverter;
import java.time.Instant;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;

/** 業務テーブルの生成クラスを模した、すべての共通カラムを持つテスト用のテーブル。 */
public final class FixtureItemTable extends TableImpl<Record> {

  private static final long serialVersionUID = 1L;

  /** テーブルのインスタンス。 */
  public static final FixtureItemTable FIXTURE_ITEM = new FixtureItemTable();

  /** 主キー。 */
  public final TableField<Record, Long> ITEM_ID =
      createField(DSL.name("item_id"), SQLDataType.BIGINT.nullable(false), this, "");

  /** コード生成が forcedType の Converter を当てる、NOT NULL の文字列カラム。 */
  public final TableField<Record, String> ITEM_NAME =
      createField(
          DSL.name("item_name"),
          SQLDataType.VARCHAR(50).nullable(false),
          this,
          "",
          new NullToEmptyStringConverter());

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

  /** 更新日時。 */
  public final TableField<Record, Instant> UPDATED_AT =
      createField(DSL.name("updated_at"), SQLDataType.INSTANT.nullable(false), this, "");

  /** 更新者。 */
  public final TableField<Record, String> UPDATED_BY =
      createField(DSL.name("updated_by"), SQLDataType.VARCHAR(64).nullable(false), this, "");

  /** 更新した機能。 */
  public final TableField<Record, String> UPDATED_PGM_CD =
      createField(DSL.name("updated_pgm_cd"), SQLDataType.VARCHAR(64).nullable(false), this, "");

  /** 更新した処理の trace ID。 */
  public final TableField<Record, String> UPDATED_TX_ID =
      createField(DSL.name("updated_tx_id"), SQLDataType.VARCHAR(32).nullable(false), this, "");

  /** ロック番号。 */
  public final TableField<Record, Long> LOCK_NO =
      createField(DSL.name("lock_no"), SQLDataType.BIGINT.nullable(false), this, "");

  /** データパッチの日時。 */
  public final TableField<Record, Instant> PATCHED_AT =
      createField(DSL.name("patched_at"), SQLDataType.INSTANT, this, "");

  /** データパッチの作業者。 */
  public final TableField<Record, String> PATCHED_BY =
      createField(DSL.name("patched_by"), SQLDataType.VARCHAR(64), this, "");

  /** データパッチの課題番号。 */
  public final TableField<Record, Integer> PATCHED_ID =
      createField(DSL.name("patched_id"), SQLDataType.INTEGER, this, "");

  private FixtureItemTable() {
    super(DSL.name("t_fixture_item"), DSL.schema(DSL.name("fixture")));
  }
}
