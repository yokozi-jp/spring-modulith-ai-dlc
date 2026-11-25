package archfixture.violating.ordering.application;

import archfixture.violating.shared.idempotency.IdempotencyKey;
import archfixture.violating.shared.idempotency.IdempotentCommand;

/**
 * 違反フィクスチャが受け取る、注文を配送し直すユースケースの入力。これ自体は規約どおり。
 *
 * @param orderId 注文 ID
 * @param idempotencyKey クライアントが採番したキー
 */
public record RedeliverOrderCommand(String orderId, IdempotencyKey idempotencyKey)
    implements IdempotentCommand {}
