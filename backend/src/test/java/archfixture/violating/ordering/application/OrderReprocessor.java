package archfixture.violating.ordering.application;

import archfixture.violating.ordering.OrderPlaced;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;

/**
 * 違反：proxiedMethodsAreNotCalledFromSameClass（プロキシで動くアノテーションを付けたメソッドを、同じクラスの中から直接、ラムダの中から、
 * 継承したメソッドとして呼ぶ。{@code @ApplicationModuleListener} の {@code on} も同じクラスから呼ぶ）。
 */
@SuppressWarnings("PMD.ShortMethodName")
public class OrderReprocessor extends OrderReprocessorBase {

  /** 再処理した注文 ID。 */
  private final Set<String> processed = ConcurrentHashMap.newKeySet();

  /** 注文をまとめて再処理する。 */
  public void reprocessAll(final List<String> orderIds) {
    archive(orderIds.getFirst());
    orderIds.forEach(orderId -> retryArchive(orderId.trim()));
    archiveWithLimit(orderIds.getLast());
    processed.add(cachedStatus(orderIds.getFirst()));
    on(new OrderPlaced(orderIds.getFirst()));
  }

  /** 注文を保管する。 */
  @Transactional
  public void archive(final String orderId) {
    processed.add(orderId);
  }

  /** 失敗したら繰り返して注文を保管する。 */
  @Retryable
  public void retryArchive(final String orderId) {
    processed.add(orderId);
  }

  /** 同時に動く数を制限して注文を保管する。 */
  @ConcurrencyLimit(1)
  public void archiveWithLimit(final String orderId) {
    processed.add(orderId);
  }

  /** 注文の受付を受けて保管する。 */
  @ApplicationModuleListener
  public void on(final OrderPlaced event) {
    processed.add(event.orderId());
  }
}
