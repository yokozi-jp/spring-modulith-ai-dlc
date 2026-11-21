package archfixture.violating.ordering.infrastructure.persistence;

import archfixture.violating.ordering.domain.model.Order;
import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.OrderRepository;
import archfixture.violating.ordering.domain.model.UnversionedOrderRepository;
import archfixture.violating.shared.infrastructure.persistence.TableWriter;

/**
 * 違反：repositoryUpdateCallsVersionedEntryPoint（update と save が TableWriter の update を呼ばず、store が
 * update という名前ではない）と onlyCommandHandlersUpdateOrDeleteAggregates（Repository の実装が別の Repository の
 * delete を呼ぶ）。
 */
public final class JooqOrderRepository implements OrderRepository {

  /** 業務テーブルの INSERT と UPDATE の入口。 */
  private final TableWriter tableWriter;

  /** 保存の代わりに削除を任せる、別の Repository。 */
  private final UnversionedOrderRepository archivedOrderRepository;

  /** 業務テーブルの INSERT と UPDATE の入口と、別の Repository を受け取る。 */
  public JooqOrderRepository(
      final TableWriter tableWriter, final UnversionedOrderRepository archivedOrderRepository) {
    this.tableWriter = tableWriter;
    this.archivedOrderRepository = archivedOrderRepository;
  }

  /** 別の Repository の delete を呼び、CommandHandler の ensureLockNo を通らずに書き込む。 */
  @Override
  public void save(final OrderId id) {
    archivedOrderRepository.delete(id);
  }

  /** TableWriter の update ではなく、版を比べない入口で注文を保存する。 */
  public String update(final Order order) {
    return tableWriter.execute(order.id().value());
  }

  /** TableWriter の update を呼ぶが、update という名前ではない。 */
  public String store(final Order order) {
    return tableWriter.update(order.id().value());
  }

  /** TableWriter に入口がない delete を、版を比べない入口で書く。 */
  public String delete(final Order order) {
    return tableWriter.execute(order.id().value());
  }

  /** update と別の名前でも、版を比べない入口で注文を保存する。 */
  public String save(final Order order) {
    return tableWriter.execute(order.id().value());
  }
}
