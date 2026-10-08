package archfixture.violating.ordering.domain.model;

/** 違反フィクスチャが実装する外部システム interface。これ自体は規約どおり。 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface PaymentGateway {

  /** 注文の代金を請求し、決済 ID を返す。 */
  String charge(OrderId orderId);
}
