package com.example.demo.shared.persistence;

import com.example.demo.jooq.DefaultCatalog;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;

/**
 * モジュールのスキーマの {@code t_idempotency_key} と、その列（ADR-126）。
 *
 * <p>キーの表は、書き込みの API を持つモジュールのスキーマに同じ形で置く。モジュールのロールは自分のスキーマの表だけを書けるため、表は呼び出し中の CommandHandler
 * のモジュールから引く。
 *
 * @param table キーの表
 * @param tenant テナントの列。既定値がトランザクションのテナントなので、INSERT では書かない
 * @param user 利用者の列
 * @param key キーの列
 * @param payloadHash ペイロードのハッシュの列
 * @param result 保存した Result の列
 * @param expiresAt 期限の列
 */
/* package */ record IdempotencyKeyTable(
    Table<?> table,
    Field<UUID> tenant,
    Field<UUID> user,
    Field<UUID> key,
    Field<byte[]> payloadHash,
    Field<JSONB> result,
    Field<Instant> expiresAt) {

  /** キーの表の名前。 */
  private static final String NAME = "t_idempotency_key";

  /**
   * モジュールのスキーマのキーの表を引く。
   *
   * @param module モジュールの名前。スキーマ名と同じである
   * @throws IllegalStateException モジュールのスキーマにキーの表がない場合
   * @throws IllegalArgumentException 表に列がない、または型が異なる場合
   */
  /* package */ static IdempotencyKeyTable ofModule(final String module) {
    final Table<?> table =
        Optional.ofNullable(DefaultCatalog.DEFAULT_CATALOG.getSchema(module))
            .map(schema -> schema.getTable(NAME))
            .orElseThrow(() ->
                new IllegalStateException(
                    "idempotency key table is missing: " + module + "." + NAME));
    return new IdempotencyKeyTable(
        table,
        CommonColumns.requiredField(table, "tenant_public_id", UUID.class),
        CommonColumns.requiredField(table, "user_public_id", UUID.class),
        CommonColumns.requiredField(table, "idempotency_key", UUID.class),
        CommonColumns.requiredField(table, "payload_hash", byte[].class),
        CommonColumns.requiredField(table, "result_json", JSONB.class),
        CommonColumns.requiredField(table, "expires_at", Instant.class));
  }
}
