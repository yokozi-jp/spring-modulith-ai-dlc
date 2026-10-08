package archfixture.violating.ordering.domain.service;

/** 違反：domainServicesAreAnnotatedWithService（domain.service のクラスに @Service がない）。 */
public class ShippingFeeCalculator {

  /** 個数から送料を計算する。 */
  public int feeFor(final int quantity) {
    return quantity * 100;
  }
}
