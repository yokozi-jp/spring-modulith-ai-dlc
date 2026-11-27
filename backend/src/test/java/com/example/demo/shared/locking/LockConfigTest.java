package com.example.demo.shared.locking;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ロックの構成を確かめる（ADR-079）。 */
class LockConfigTest {

  @Test
  @DisplayName("ロックの表は search_path に頼らず modulith スキーマで描画する")
  void lockTableIsRenderedWithModulithSchema() {
    final DSLContext lockDsl = LockConfig.lockDsl(DSL.using(SQLDialect.POSTGRES));

    assertThat(lockDsl.render(lockDsl.selectFrom(DSL.table(DSL.name("shedlock")))))
        .contains("\"modulith\".\"shedlock\"");
  }
}
