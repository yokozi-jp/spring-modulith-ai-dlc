package com.example.demo.ordering.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.testkit.DatabaseTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code @DatabaseTest} のトランザクションが、テストクラスの名前から ordering のロールで動くことを確かめる（ADR-094）。 */
@DatabaseTest
class OrderModuleRoleTest {

  /** テスト用の DB の jOOQ のコンテキスト。テストごとにロールバックする。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("ordering のテストクラスのトランザクションは、ordering のロールで動く")
  void testTransactionRunsAsModuleRole() {
    assertThat(dsl.fetchValue("select current_user")).isEqualTo("ordering_module");
  }
}
