package com.example.demo.testkit.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link TableRows.Row} の比べ方を確かめる。 */
class TableRowsTest {

  /** 配列の列。 */
  private static final Field<byte[]> DATA = DSL.field(DSL.name("data"), SQLDataType.BLOB);

  @Test
  @DisplayName("配列の列は中身で比べ、同じ中身の行を同じとし、違う中身の行を違うとする")
  void arrayColumnsCompareByContent() {
    assertThat(row(new byte[] {1, 2}))
        .isEqualTo(row(new byte[] {1, 2}))
        .isNotEqualTo(row(new byte[] {1, 3}));
  }

  private static TableRows.Row row(final byte[] data) {
    final Record record = DSL.using(SQLDialect.POSTGRES).newRecord(DATA);
    record.set(DATA, data);
    return TableRows.Row.from(record);
  }
}
