package com.example.demo.shared.persistence;

import com.example.demo.shared.idempotency.Idempotency;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.idempotency.IdempotencyKeyException;
import com.example.demo.shared.idempotency.IdempotentCommand;
import com.example.demo.shared.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record2;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Idempotency} を、呼び出し中の CommandHandler のモジュールのキーの表で実装する（ADR-126）。
 *
 * <p>操作の名前は {@code *_pgm_cd}（{@code ordering.ConfirmOrder}）で、キーの表はそのモジュールのスキーマから引く。モジュールごとの表は起動のときに
 * jOOQ のカタログから一度だけ引き、列を確かめる。ペイロードのハッシュは、操作の名前と Command の JSON の SHA-256 である。Result は JSON で保存する。
 *
 * <p>JSON は API の {@code spring.jackson.*} の設定から切り離した既定の形で作り、設定を変えてもハッシュと保存した形が変わらないようにする。保存した
 * Result は配備をまたいで読み直すので、今の Result の型にないプロパティは無視する。
 */
@Component
/* package */ class JooqIdempotency implements Idempotency {

  /** Command と Result の JSON。 */
  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** 保存したキーを読む jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** キーの行を書く。 */
  private final IdempotencyKeyWriter writer;

  /** モジュールの名前からキーの表への対応。 */
  private final Map<String, IdempotencyKeyTable> tables;

  /** 期限の基準の現在時刻を取る時計。 */
  private final Clock clock;

  /**
   * jOOQ のカタログのキーの表で作る。
   *
   * @throws IllegalArgumentException キーの表に列がない、または型が異なる場合
   */
  /* package */ JooqIdempotency(
      final DSLContext dsl, final CommonColumns commonColumns, final Clock clock) {
    this.dsl = dsl;
    this.writer = new IdempotencyKeyWriter(dsl, commonColumns);
    this.tables = IdempotencyKeyTable.inCatalog();
    this.clock = clock;
  }

  @Override
  public <R> R execute(
      final IdempotentCommand command, final Class<R> resultType, final Supplier<R> operation) {
    final String module = PgmCdAspect.currentModule();
    final IdempotencyKeyTable keys =
        Optional.ofNullable(tables.get(module))
            .orElseThrow(() ->
                new IllegalStateException("idempotency key table is missing: module=" + module));
    final IdempotencyKeyTable.ScopedKey scopedKey =
        new IdempotencyKeyTable.ScopedKey(
            TenantContext.current().value(), currentUser(), command.idempotencyKey().value());
    final String pgmCd = PgmCdAspect.current();
    final byte[] payloadHash = payloadHash(pgmCd, command);
    final Instant now = Instant.now(clock);
    if (writer.claim(keys, scopedKey, payloadHash, now, now.plus(IdempotencyKey.RETENTION))) {
      final R result = operation.get();
      writer.saveResult(keys, scopedKey, JSONB.valueOf(JSON.writeValueAsString(result)));
      return result;
    }
    // claim が 0 行なら、commit 済みの有効な行がある。ON CONFLICT DO UPDATE がその行をロックしたので、消えない。
    final Record2<byte[], JSONB> stored =
        dsl.select(keys.payloadHash(), keys.result())
            .from(keys.table())
            .where(keys.identifies(scopedKey))
            .fetchSingle();
    if (!MessageDigest.isEqual(stored.value1(), payloadHash)) {
      throw new IdempotencyKeyException(
          IdempotencyKeyException.Reason.PAYLOAD_MISMATCH,
          "Idempotency-Key was used for a different payload: operation="
              + pgmCd
              + ", key="
              + scopedKey.key());
    }
    final @Nullable JSONB result = stored.value2();
    if (result == null) {
      throw new IllegalStateException(
          "committed idempotency key has no result: key=" + scopedKey.key());
    }
    return JSON.readValue(result.data(), resultType);
  }

  /** 操作の名前と Command の JSON の SHA-256 を返す。 */
  private static byte[] payloadHash(final String operation, final IdempotentCommand command) {
    try {
      final MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(operation.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
      return digest.digest(JSON.writeValueAsBytes(command));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", e);
    }
  }

  /** キーを分ける利用者の ID を返す（ADR-096）。 */
  private static UUID currentUser() {
    return UUID.fromString(
        CommonColumns.currentAppUserId()
            .orElseThrow(() ->
                new IllegalStateException("Idempotency-Key requires an authenticated app user")));
  }
}
