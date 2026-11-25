package com.example.demo.shared.idempotency;

/** {@code Idempotency-Key} を持つ Command の印。HTTP の要求から作る、GET と HEAD 以外の操作の Command が実装する（ADR-126）。 */
// Command の印であり、ラムダで実装する関数型ではないため、@FunctionalInterface にしない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface IdempotentCommand {

  /** クライアントが利用者の操作ごとに採番したキーを返す。 */
  IdempotencyKey idempotencyKey();
}
