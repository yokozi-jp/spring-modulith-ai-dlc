package archfixture.violating.shared.idempotency;

import java.util.UUID;

/**
 * クライアントが利用者の操作ごとに採番するキー。
 *
 * @param value キーの UUID
 */
public record IdempotencyKey(UUID value) {}
