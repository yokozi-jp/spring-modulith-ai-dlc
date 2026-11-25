package com.example.demo.shared.idempotency;

import com.example.demo.shared.concurrency.ConflictException;
import java.util.function.Supplier;

/**
 * CommandHandler のトランザクションの中で、同じ {@code Idempotency-Key} の再送に保存した Result を返す（ADR-126）。
 *
 * <p>キーはモジュールのスキーマのキーの表に、業務の更新と同じトランザクションで書く。業務の処理が例外で終わったら rollback し、キーの行を残さない。
 */
// CommandHandler が handle の中で呼ぶ入口であり、ラムダで実装する関数型ではないため、@FunctionalInterface にしない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface Idempotency {

  /**
   * キーが新しいか期限切れなら業務の処理を実行して Result を保存し、有効なキーがあれば保存した Result を返す。
   *
   * <p>CommandHandler の {@code handle} の中で呼ぶ。キーは、テナント、利用者、キーの組で分ける。ペイロードは、操作の名前と Command
   * から作るハッシュで比べる。
   *
   * @param command キーを持つ Command
   * @param resultType 保存した Result を読み直す型
   * @param operation 業務の処理。キーが新しいか期限切れのときだけ呼ぶ
   * @return 業務の処理の Result か、保存した Result
   * @throws IdempotencyKeyException 有効なキーのペイロードが違う場合（{@code PAYLOAD_MISMATCH}）
   * @throws ConflictException 同じキーの要求の終わりを {@code lock_timeout} までに待ち切れなかった場合（種類は {@code
   *     IDEMPOTENCY_IN_PROGRESS}）。原因は持たない
   * @throws IllegalStateException CommandHandler の外で呼んだ場合、利用者が認証されていない場合
   */
  <R> R execute(IdempotentCommand command, Class<R> resultType, Supplier<R> operation);
}
