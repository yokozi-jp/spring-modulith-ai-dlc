import type { IdempotencyKeyParameter } from "./generated/models";

/**
 * 利用者の操作 1 回分の新しい Idempotency-Key ヘッダーを、契約の形（引用符で囲んだ UUID）で作る（ADR-126）。
 * 呼び方は docs/frontend/file-roles/feature-hook.md に従う。
 */
export function idempotencyKeyHeaders(): { "Idempotency-Key": IdempotencyKeyParameter } {
  return { "Idempotency-Key": `"${crypto.randomUUID()}"` };
}
