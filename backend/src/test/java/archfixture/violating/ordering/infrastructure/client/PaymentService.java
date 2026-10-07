package archfixture.violating.ordering.infrastructure.client;

import org.springframework.stereotype.Service;

/** 違反：servicesResideInApplicationOrDomainService（Adapter に @Service を付ける）。 */
@Service
public class PaymentService {

  /** 決済の受付番号を返す。 */
  public long nextReceiptNumber(final long previous) {
    return previous + 1;
  }
}
