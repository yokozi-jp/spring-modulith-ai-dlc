package archfixture.violating.order.domain.model;

/**
 * 違反：repositoryWritesTakeVersionedAggregates（add と update が集約ルートではなく識別子を受け取る）。
 *
 * <p>{@code save} は、update と別の名前で集約ルートを書き込む経路を作る。
 */
public interface UnversionedOrderRepository {

  /** update と別の名前で注文を保存する。 */
  void save(Order order);

  /** 版を持たない識別子で新しい注文を保存する。 */
  void add(OrderId id);

  /** 版を持たない識別子で注文を保存する。 */
  void update(OrderId id);

  /** 版を持たない識別子で注文を削除する。 */
  void delete(OrderId id);
}
