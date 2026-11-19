package archfixture.violating.ordering.application;

import archfixture.violating.ordering.OrderPlaced;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

/**
 * 違反：proxiedMethodsAreNotPrivateFinalOrStatic（プロキシで動くアノテーションを、プロキシが上書きできない final、static、private
 * のメソッドに付ける）。
 */
@SuppressWarnings("PMD.ShortMethodName")
public class OrderArchiver {

  /** 保管した注文 ID。 */
  private final Set<String> archived = ConcurrentHashMap.newKeySet();

  /** 注文を保管する。 */
  @Transactional
  public final void archive(final String orderId) {
    archived.add(orderId);
  }

  /** 注文 ID を正規化する。 */
  @PreAuthorize("hasRole('ADMIN')")
  public static String normalize(final String orderId) {
    return orderId.trim();
  }

  // 違反の形を作るため、呼ばない private メソッドに @Retryable を付ける。
  @SuppressWarnings({"UnusedMethod", "unused"})
  @Retryable
  private void retryArchive() {
    archived.clear();
  }

  /** 注文の受付を受けて保管する。 */
  @ApplicationModuleListener
  public final void on(final OrderPlaced event) {
    archived.add(event.orderId());
  }
}
