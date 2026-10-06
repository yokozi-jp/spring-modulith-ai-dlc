package archfixture.conforming.order.application;

import archfixture.conforming.shared.concurrency.ExpectedLockNo;
import archfixture.conforming.shared.concurrency.VersionedCommand;

/** 画面から注文を取り消すユースケースの入力。画面が表示した版を持つ。 */
public record CancelOrderCommand(String orderId, ExpectedLockNo expectedLockNo)
    implements VersionedCommand {}
