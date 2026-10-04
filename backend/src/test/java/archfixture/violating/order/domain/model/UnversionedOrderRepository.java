package archfixture.violating.order.domain.model;

/** 違反：repositoryUpdateAndDeleteTakeVersionedAggregates（update が集約ルートではなく識別子を受け取る）。 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface UnversionedOrderRepository {

  /** 版を持たない識別子で注文を保存する。 */
  void update(OrderId id);
}
