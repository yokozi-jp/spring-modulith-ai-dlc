package com.example.demo.shared.persistence;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.idempotency.Idempotency;
import com.example.demo.shared.idempotency.IdempotencyKeyInProgressException;
import com.example.demo.shared.idempotency.IdempotencyKeyPayloadMismatchException;
import com.example.demo.shared.idempotency.IdempotentCommand;
import com.example.demo.shared.security.AppUserPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record2;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Idempotency} を、呼び出し中の CommandHandler のモジュールのキーの表で実装する（ADR-126）。
 *
 * <p>操作の名前は {@code *_pgm_cd}（{@code
 * ordering.ConfirmOrder}）で、キーの表はその先頭のモジュールのスキーマから引く。ペイロードのハッシュは、操作の名前と Command の JSON の SHA-256
 * である。Result は JSON で保存する。
 *
 * <p>JSON は API の {@code spring.jackson.*} の設定から切り離した既定の形で作り、設定を変えてもハッシュと保存した形が変わらないようにする。保存した
 * Result は配備をまたいで読み直すので、今の Result の型にないプロパティは無視する。
 */
@Component
/* package */ class JooqIdempotency implements Idempotency {

  /** キーを保存する期間。docs と OpenAPI で公開する。 */
  private static final Duration RETENTION = Duration.ofHours(24);

  /** Command と Result の JSON。 */
  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** 保存したキーを読む jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** キーの行を書く。 */
  private final TableWriter tableWriter;

  /** 期限の基準の現在時刻を取る時計。 */
  private final Clock clock;

  /** 依存を受け取る。 */
  /* package */ JooqIdempotency(
      final DSLContext dsl, final TableWriter tableWriter, final Clock clock) {
    this.dsl = dsl;
    this.tableWriter = tableWriter;
    this.clock = clock;
  }

  @Override
  public <R> R execute(
      final IdempotentCommand command, final Class<R> resultType, final Supplier<R> operation) {
    final String pgmCd = PgmCdAspect.current();
    final IdempotencyKeyTable keys =
        IdempotencyKeyTable.ofModule(pgmCd.substring(0, pgmCd.indexOf('.')));
    final UUID user = currentUser();
    final UUID key = command.idempotencyKey().value();
    final byte[] payloadHash = payloadHash(pgmCd, command);
    final Instant now = Instant.now(clock);
    if (claim(keys, user, key, payloadHash, now)) {
      final R result = operation.get();
      tableWriter.saveIdempotentResult(
          keys, user, key, JSONB.valueOf(JSON.writeValueAsString(result)));
      return result;
    }
    // claim が 0 行なら、commit 済みの有効な行がある。ON CONFLICT DO UPDATE がその行をロックしたので、消えない。
    final Record2<byte[], JSONB> stored =
        dsl.select(keys.payloadHash(), keys.result())
            .from(keys.table())
            .where(keys.user().eq(user).and(keys.key().eq(key)))
            .fetchSingle();
    if (!MessageDigest.isEqual(stored.value1(), payloadHash)) {
      throw new IdempotencyKeyPayloadMismatchException(
          "Idempotency-Key was used for a different payload: operation=" + pgmCd + ", key=" + key);
    }
    final @Nullable JSONB result = stored.value2();
    if (result == null) {
      throw new IllegalStateException("committed idempotency key has no result: key=" + key);
    }
    return JSON.readValue(result.data(), resultType);
  }

  /** キーの行を取る。同じキーの要求の終わりを待ち切れなかったら、処理中の例外にする。 */
  // ConflictException は原因を持たず、メッセージは処理中の例外に書き直すので、捨てるスタックトレースに情報はない（ADR-062）。
  @SuppressWarnings("PMD.PreserveStackTrace")
  private boolean claim(
      final IdempotencyKeyTable keys,
      final UUID user,
      final UUID key,
      final byte[] payloadHash,
      final Instant now) {
    try {
      return tableWriter.claimIdempotencyKey(
          keys, user, key, payloadHash, now, now.plus(RETENTION));
    } catch (final ConflictException e) {
      if (e.kind() != ConflictException.Kind.LOCK) {
        throw e;
      }
      throw new IdempotencyKeyInProgressException(
          "request with the same Idempotency-Key is in progress: key=" + key);
    }
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
    final @Nullable Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal user) {
      return UUID.fromString(user.userId());
    }
    throw new IllegalStateException("Idempotency-Key requires an authenticated app user");
  }
}
