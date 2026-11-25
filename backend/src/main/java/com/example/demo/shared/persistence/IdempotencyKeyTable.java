package com.example.demo.shared.persistence;

import com.example.demo.jooq.DefaultCatalog;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Schema;
import org.jooq.Table;

/**
 * モジュールのスキーマの {@code t_idempotency_key} と、その列（ADR-126）。
 *
 * <p>キーの表は、書き込みの API を持つモジュールのスキーマに同じ形で置く。モジュールのロールは自分のスキーマの表だけを書けるため、表は呼び出し中の CommandHandler
 * のモジュールから引く。
 *
 * @param table キーの表
 * @param id 主キーの列
 * @param tenant テナントの列
 * @param user 利用者の列
 * @param key キーの列
 * @param payloadHash ペイロードのハッシュの列
 * @param result 保存した Result の列
 * @param expiresAt 期限の列
 * @param lockNo 版の列
 */
/* package */ record IdempotencyKeyTable(
    Table<?> table,
    Field<Long> id,
    Field<UUID> tenant,
    Field<UUID> user,
    Field<UUID> key,
    Field<byte[]> payloadHash,
    Field<JSONB> result,
    Field<Instant> expiresAt,
    Field<Long> lockNo) {

  /** キーの表の名前。 */
  private static final String NAME = "t_idempotency_key";

  /**
   * jOOQ の生成したカタログで、キーの表を持つスキーマごとに表を引く。
   *
   * @return スキーマの名前（モジュールの名前と同じ）からキーの表への対応
   * @throws IllegalArgumentException 表に列がない、または型が異なる場合
   */
  /* package */ static Map<String, IdempotencyKeyTable> inCatalog() {
    return DefaultCatalog.DEFAULT_CATALOG.getSchemas().stream()
        .filter(schema -> schema.getTable(NAME) != null)
        .collect(
            Collectors.toUnmodifiableMap(
                Schema::getName, schema -> from(Objects.requireNonNull(schema.getTable(NAME)))));
  }

  /**
   * 表の列を名前と型で確かめて引く。
   *
   * @throws IllegalArgumentException 表に列がない、または型が異なる場合
   */
  private static IdempotencyKeyTable from(final Table<?> table) {
    CommonColumns.requireAuditColumns(table);
    return new IdempotencyKeyTable(
        table,
        CommonColumns.requiredField(table, "idempotency_key_id", Long.class),
        CommonColumns.requiredField(table, "tenant_public_id", UUID.class),
        CommonColumns.requiredField(table, "user_public_id", UUID.class),
        CommonColumns.requiredField(table, "idempotency_key", UUID.class),
        CommonColumns.requiredField(table, "payload_hash", byte[].class),
        CommonColumns.requiredField(table, "result_json", JSONB.class),
        CommonColumns.requiredField(table, "expires_at", Instant.class),
        CommonColumns.requiredField(table, CommonColumns.LOCK_NO, Long.class));
  }

  /** テナント、利用者、キーの組の行を指す条件を返す。RLS のない表でも別のテナントに当たらないよう、テナントも条件に入れる。 */
  /* package */ Condition identifies(final ScopedKey scopedKey) {
    return tenant
        .eq(scopedKey.tenant())
        .and(user.eq(scopedKey.user()))
        .and(key.eq(scopedKey.key()));
  }

  /**
   * テナントと利用者で分けたキー。キーの表のユニークインデックスの組である。
   *
   * @param tenant テナントの {@code public_id}
   * @param user アプリの利用者の ID
   * @param key クライアントが採番したキー
   */
  /* package */ record ScopedKey(UUID tenant, UUID user, UUID key) {}
}
