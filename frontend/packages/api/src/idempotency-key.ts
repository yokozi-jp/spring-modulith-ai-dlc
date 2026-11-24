/**
 * 利用者の操作 1 回分の Idempotency-Key ヘッダー（ADR-126）。値は Structured Field の String で、中身は UUID にする。
 * 操作のたびに呼んで新しいキーを採り、mutation の変数に入れる。同じキーを送るのは、同じ変数で呼ばれる自動の再試行だけにする。
 */
export function idempotencyKeyHeaders() {
  return { "Idempotency-Key": `"${crypto.randomUUID()}"` };
}
