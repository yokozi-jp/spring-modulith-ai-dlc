package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;

import java.time.Instant;
import org.jooq.Field;

/** JooqCommonColumnsArchTest が許可の経路を検証するため、shared の共通処理から共通カラムを参照するフィクスチャ。 */
public final class AllowedCommonColumnAccess {

  private AllowedCommonColumnAccess() {}

  /** 生成クラスの {@code CREATED_AT} を返す。 */
  public static Field<Instant> createdAt() {
    return FIXTURE_ITEM.CREATED_AT;
  }
}
