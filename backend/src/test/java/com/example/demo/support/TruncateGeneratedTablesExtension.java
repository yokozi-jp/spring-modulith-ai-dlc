package com.example.demo.support;

import com.example.demo.jooq.DefaultSchema;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 各テストの後に、jOOQ が生成したアプリケーションテーブルをすべて {@code TRUNCATE} する JUnit 拡張。
 *
 * <p>生成対象は Liquibase 管理テーブル（{@code DATABASECHANGELOG} など）を codegen で除外済みなので、
 * マイグレーション状態を壊さずアプリのデータだけを消す。テーブルを追加して codegen を再生成すれば、 後始末の対象も自動で増える。コミットを伴う {@link
 * CommittedDatabaseTest} 用の後始末機構。
 */
public final class TruncateGeneratedTablesExtension implements AfterEachCallback {

  @Override
  public void afterEach(final ExtensionContext context) {
    final DSLContext dslContext =
        SpringExtension.getApplicationContext(context).getBean(DSLContext.class);
    for (final Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
      dslContext.truncate(table).cascade().execute();
    }
  }
}
