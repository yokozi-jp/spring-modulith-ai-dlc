package com.example.demo.jooq.tables;

import java.time.Instant;
import java.util.UUID;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.TableField;
import org.jooq.impl.SQLDataType;

/**
 * モジュールの {@code t_idempotency_key} と同じ列を持ち、RLS を付けないテスト用のテーブル。
 *
 * <p>RLS が隠すより前に、条件のテナントだけで別のテナントの行を分けることを確かめるために使う。主キーはキーの組にする。
 */
public final class FixtureIdempotencyKeyTable extends FixtureBusinessTable {

  private static final long serialVersionUID = 1L;

  /** テーブルのインスタンス。 */
  public static final FixtureIdempotencyKeyTable FIXTURE_IDEMPOTENCY_KEY =
      new FixtureIdempotencyKeyTable();

  /** テナント。既定値を持たない。 */
  public final TableField<Record, UUID> TENANT_PUBLIC_ID =
      notNull("tenant_public_id", SQLDataType.UUID);

  /** 利用者。 */
  public final TableField<Record, UUID> USER_PUBLIC_ID =
      notNull("user_public_id", SQLDataType.UUID);

  /** キー。 */
  public final TableField<Record, UUID> IDEMPOTENCY_KEY =
      notNull("idempotency_key", SQLDataType.UUID);

  /** ペイロードのハッシュ。 */
  public final TableField<Record, byte[]> PAYLOAD_HASH = notNull("payload_hash", SQLDataType.BLOB);

  /** 保存した Result。 */
  public final TableField<Record, JSONB> RESULT_JSON = nullable("result_json", SQLDataType.JSONB);

  /** 期限。 */
  public final TableField<Record, Instant> EXPIRES_AT = notNull("expires_at", SQLDataType.INSTANT);

  private FixtureIdempotencyKeyTable() {
    super("t_idempotency_key");
  }
}
