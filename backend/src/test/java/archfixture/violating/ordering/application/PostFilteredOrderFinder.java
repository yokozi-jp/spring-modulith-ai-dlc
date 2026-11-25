package archfixture.violating.ordering.application;

import java.util.List;
import org.springframework.security.access.prepost.PostFilter;

/** 違反：applicationDependsOnlyOnAllowedTypes（@PreAuthorize でない method security の @PostFilter を使う）。 */
public class PostFilteredOrderFinder {

  /** すべての注文を読み、持ち主でない注文をメモリで捨てる。 */
  @PostFilter("filterObject == authentication.name")
  public List<String> findAll() {
    return List.of("order-1");
  }
}
