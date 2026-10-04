package archfixture.violating.order.domain.model;

/** 違反：repositoryWritesTakeVersionedAggregates（add と update が集約ルートではなく識別子を受け取る）。 */
public interface UnversionedOrderRepository {

  /** 版を持たない識別子で新しい注文を保存する。 */
  void add(OrderId id);

  /** 版を持たない識別子で注文を保存する。 */
  void update(OrderId id);
}
