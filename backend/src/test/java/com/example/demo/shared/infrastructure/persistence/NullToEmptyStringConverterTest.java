package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** NULL を空文字へそろえる Converter が、直接の呼び出しと jOOQ の読み書きの両方で働くことを検証する。 */
class NullToEmptyStringConverterTest {

  /** 生成コードと同じ方法で Converter を当てた、NOT NULL の varchar カラム。 */
  private static final Field<String> ITEM_NAME =
      DSL.field(
          DSL.name("s", "t", "item_name"),
          SQLDataType.VARCHAR(50)
              .nullable(false)
              .asConvertedDataType(new NullToEmptyStringConverter()));

  /** DB が返す NULL を模すための、Converter を当てていない同名のカラム。 */
  private static final Field<String> RAW_ITEM_NAME =
      DSL.field(DSL.name("s", "t", "item_name"), SQLDataType.VARCHAR(50));

  private static final Table<Record> TABLE = DSL.table(DSL.name("s", "t"));

  /** 書き込みで発行された SQL とバインド値。 */
  private final List<MockExecuteContext> executed = new ArrayList<>();

  @Test
  @DisplayName("null は空文字に、それ以外の値はそのままに変換する")
  void convertsNullToEmptyAndKeepsOtherValues() {
    final NullToEmptyStringConverter converter = new NullToEmptyStringConverter();

    assertThat(converter.from(null)).as("DB からの null").isEmpty();
    assertThat(converter.to(null)).as("DB への null").isEmpty();
    assertThat(converter.from("abc")).as("DB からの値").isEqualTo("abc");
    assertThat(converter.to("abc")).as("DB への値").isEqualTo("abc");
    assertThat(converter.fromType()).as("DB 側の型").isEqualTo(String.class);
    assertThat(converter.toType()).as("アプリケーション側の型").isEqualTo(String.class);
  }

  @Test
  @DisplayName("DB から読んだ NULL を空文字で返す")
  void readsDatabaseNullAsEmptyString() {
    final DSLContext dsl = dslReturningNull();

    final List<String> names = dsl.select(ITEM_NAME).from(TABLE).fetch(ITEM_NAME);

    assertThat(names).as("NULL を読んだ結果").containsExactly("");
  }

  @Test
  @DisplayName("INSERT の set へ渡した null を空文字でバインドする")
  void insertBindsNullAsEmptyString() {
    recordingDsl().insertInto(TABLE).set(ITEM_NAME, (String) null).execute();

    assertBoundAsEmptyString();
  }

  @Test
  @DisplayName("UPDATE の set へ渡した null を空文字でバインドする")
  void updateBindsNullAsEmptyString() {
    recordingDsl().update(TABLE).set(ITEM_NAME, (String) null).execute();

    assertBoundAsEmptyString();
  }

  @Test
  @DisplayName("Map で渡した null を空文字でバインドする")
  void mapSetBindsNullAsEmptyString() {
    // Map.of は null を受け付けないため、null を値に持てる HashMap を使う。
    final Map<Field<?>, Object> values = new HashMap<>();
    values.put(ITEM_NAME, null);

    recordingDsl().update(TABLE).set(values).execute();

    assertBoundAsEmptyString();
  }

  private void assertBoundAsEmptyString() {
    assertThat(executed).as("発行した文").hasSize(1);
    final MockExecuteContext context = executed.getFirst();
    assertThat(context.sql()).as("null をリテラルで埋め込まないこと").doesNotContainIgnoringCase("null");
    assertThat(context.bindings()).as("バインド値").containsExactly("");
  }

  private DSLContext recordingDsl() {
    return DSL.using(
        new MockConnection(
            context -> {
              executed.add(context);
              return new MockResult[] {new MockResult(1)};
            }),
        SQLDialect.POSTGRES);
  }

  private static DSLContext dslReturningNull() {
    final DSLContext builder = DSL.using(SQLDialect.POSTGRES);
    final Result<Record1<String>> result = builder.newResult(RAW_ITEM_NAME);
    final Record1<String> row = builder.newRecord(RAW_ITEM_NAME);
    row.value1(null);
    result.add(row);
    return DSL.using(
        new MockConnection(context -> new MockResult[] {new MockResult(1, result)}),
        SQLDialect.POSTGRES);
  }
}
