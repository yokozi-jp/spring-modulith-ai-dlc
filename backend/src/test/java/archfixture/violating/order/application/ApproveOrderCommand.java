package archfixture.violating.order.application;

import archfixture.violating.shared.concurrency.ExpectedLockNo;
import archfixture.violating.shared.concurrency.VersionedCommand;

/** 違反フィクスチャが受け取る、画面が表示した版を持つ Command。これ自体は規約どおり。 */
public record ApproveOrderCommand(String orderId, ExpectedLockNo expectedLockNo)
    implements VersionedCommand {}
