package archfixture.violating.ordering.infrastructure.persistence;

import archfixture.violating.ordering.domain.model.Order;
import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.OrderRepository;
import archfixture.violating.ordering.domain.model.UnversionedOrderRepository;
import archfixture.violating.shared.infrastructure.persistence.TableWriter;

/**
 * 違反：repositoryUpdateAndDeleteCheckVersion（update と save が updateCheckingVersion を呼ばず、updateStatus
 * が集約ルートの lockNo() を渡さない）と aggregateMethodsDoNotUseUnversionedWrites（集約ルートを受け取るメソッドが updateWhere
 * を呼ぶ）と onlyCommandHandlersUpdateOrDeleteAggregates（Repository の実装が別の Repository の delete を呼ぶ）。
 */
public final class JooqOrderRepository implements OrderRepository {

  /** 業務テーブルの UPDATE と DELETE の入口。 */
  private final TableWriter tableWriter;

  /** 保存の代わりに削除を任せる、別の Repository。 */
  private final UnversionedOrderRepository archivedOrderRepository;

  /** 業務テーブルの UPDATE と DELETE の入口と、別の Repository を受け取る。 */
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

  /** 版を比べずに注文を保存し、件数を返す。 */
  public int update(final Order order) {
    return tableWriter.updateWhere(order.id().value());
  }

  /** update と別の名前で、識別子だけを受け取るメソッドに書き込みを任せる。 */
  public int save(final Order order) {
    return writeColumns(order.id().value());
  }

  /** 集約ルートの版ではなく、テーブルから読み直した版を期待する版に渡す。 */
  public String updateStatus(final Order order) {
    return tableWriter.updateCheckingVersion("orders", currentLockNo(order.id().value()));
  }

  private int writeColumns(final String orderId) {
    return tableWriter.updateWhere(orderId);
  }

  /** テーブルの今の版を読み直したものとする。 */
  private long currentLockNo(final String orderId) {
    return orderId.length();
  }
}
