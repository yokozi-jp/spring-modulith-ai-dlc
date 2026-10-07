package archfixture.violating.ordering.domain.model;

/** 違反フィクスチャが実装する Repository。これ自体は規約どおり。 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface OrderRepository {

  /** 注文 ID を保存する。 */
  void save(OrderId id);
}
