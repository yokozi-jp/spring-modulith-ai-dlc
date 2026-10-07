package archfixture.conforming.ordering.domain.model;

/** 外部の決済システム。 */
// 外部システムの interface であり、ラムダで実装する関数型 interface ではない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PaymentGateway {

  /** 注文の代金を請求する。 */
  PaymentId charge(OrderId orderId);
}
