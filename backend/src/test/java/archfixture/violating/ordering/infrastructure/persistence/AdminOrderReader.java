package archfixture.violating.ordering.infrastructure.persistence;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 違反：methodSecurityAnnotationsAreOnlyOnApplicationClasses（infrastructure のクラスに @PreAuthorize を付ける）。
 */
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrderReader {

  /** すべての注文の ID を読む。 */
  public List<String> readAll() {
    return List.of("order-1");
  }
}
