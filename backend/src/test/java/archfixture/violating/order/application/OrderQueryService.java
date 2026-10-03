package archfixture.violating.order.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 違反：queryServicesImplementModuleQueries（モジュールの *Queries を実装しない）。 */
@Service
public class OrderQueryService {

  /** 顧客の注文 ID を返す。 */
  @Transactional(readOnly = true)
  public List<String> orderIdsOf(final String customerId) {
    return List.of(customerId);
  }
}
