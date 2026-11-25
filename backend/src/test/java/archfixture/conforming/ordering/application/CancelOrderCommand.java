package archfixture.conforming.ordering.application;

import archfixture.conforming.shared.concurrency.ExpectedLockNo;
import archfixture.conforming.shared.concurrency.VersionedCommand;
import archfixture.conforming.shared.idempotency.IdempotencyKey;
import archfixture.conforming.shared.idempotency.IdempotentCommand;

/** 画面から注文を取り消すユースケースの入力。画面が表示した版を持つ。 */
public record CancelOrderCommand(
    String orderId, ExpectedLockNo expectedLockNo, IdempotencyKey idempotencyKey)
    implements VersionedCommand, IdempotentCommand {}
