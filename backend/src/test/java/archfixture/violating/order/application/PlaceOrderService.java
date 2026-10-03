package archfixture.violating.order.application;

import org.springframework.stereotype.Service;

/** 違反：applicationServicesHaveRoleNames（application の @Service を *Service と命名する）。 */
@Service
public class PlaceOrderService {

  /** 注文を受け付けたかを返す。 */
  public boolean place(final String customerId) {
    return !customerId.isBlank();
  }
}
