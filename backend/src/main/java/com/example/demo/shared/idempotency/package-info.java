/**
 * {@code Idempotency-Key} の語彙と、CommandHandler が呼ぶ入口を置く（ADR-126）。ヘッダーの値の {@link
 * com.example.demo.shared.idempotency.IdempotencyKey}、それを持つ Command の印の {@link
 * com.example.demo.shared.idempotency.IdempotentCommand}、保存した Result を返す入口の {@link
 * com.example.demo.shared.idempotency.Idempotency}、クライアントの誤りの {@link
 * com.example.demo.shared.idempotency.IdempotencyKeyException} である。同じキーの処理中は {@code
 * shared.concurrency} の {@code ConflictException} の種類で表す。
 *
 * <p>Domain 以外のどの層からも使ってよい。{@link com.example.demo.shared.idempotency.Idempotency} の実装は {@code
 * shared.persistence} に置き、このパッケージは Spring、HTTP、jOOQ に依存しない。
 */
@NamedInterface("idempotency")
@NullMarked
package com.example.demo.shared.idempotency;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
