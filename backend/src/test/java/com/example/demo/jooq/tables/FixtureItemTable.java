package com.example.demo.jooq.tables;

import com.example.demo.shared.persistence.NullToEmptyStringConverter;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/** 業務テーブルの生成クラスを模した、すべての共通カラムを持つテスト用のテーブル。 */
public final class FixtureItemTable extends FixtureBusinessTable {

  private static final long serialVersionUID = 1L;

  /** テーブルのインスタンス。 */
  public static final FixtureItemTable FIXTURE_ITEM = new FixtureItemTable();

  /** 主キー。 */
  public final TableField<Record, Long> ITEM_ID = notNull("item_id", SQLDataType.BIGINT);

  /** コード生成が forcedType の Converter を当てる、NOT NULL の文字列カラム。 */
  public final TableField<Record, String> ITEM_NAME =
      createField(
          DSL.name("item_name"),
          SQLDataType.VARCHAR(50).nullable(false),
          this,
          "",
          new NullToEmptyStringConverter());

  /** 主キーのほかに一意制約を持つ列。NULL を許すため、この列を書かない行どうしは衝突しない。 */
  public final TableField<Record, String> ITEM_CODE =
      nullable("item_code", SQLDataType.VARCHAR(20));

  private FixtureItemTable() {
    super("t_fixture_item");
  }
}
