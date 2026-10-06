package archfixture.violating.order.application;

import archfixture.violating.order.domain.model.Order;
import archfixture.violating.shared.concurrency.ExpectedLockNo;
import java.util.function.LongFunction;
import org.springframework.stereotype.Service;

/** 違反：expectedLockNoIsCreatedOnlyByRequests（CommandHandler が読んだ版から ExpectedLockNo を作る）。 */
@Service
public class ForgedLockNoCommandHandler {

  /** 読んだ版を期待する版として作り、その値を返す。 */
  public long forged(final Order order) {
    return new ExpectedLockNo(order.lockNo()).value();
  }

  /** コンストラクタ参照で期待する版を作り、その値を返す。 */
  public long forgedByReference(final Order order) {
    final LongFunction<ExpectedLockNo> factory = ExpectedLockNo::new;
    return factory.apply(order.lockNo()).value();
  }
}
