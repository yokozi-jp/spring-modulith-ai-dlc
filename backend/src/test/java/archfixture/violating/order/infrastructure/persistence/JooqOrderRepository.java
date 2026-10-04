package archfixture.violating.order.infrastructure.persistence;

import archfixture.violating.order.domain.model.Order;
import archfixture.violating.shared.infrastructure.persistence.TableWriter;

/**
 * 違反：repositoryUpdateAndDeleteCheckVersion（update と save が updateCheckingVersion を呼ばない）と
 * aggregateMethodsDoNotUseUnversionedWrites（集約ルートを受け取るメソッドが updateWhere を呼ぶ）。
 */
public final class JooqOrderRepository {

  /** 業務テーブルの UPDATE と DELETE の入口。 */
  private final TableWriter tableWriter;

  /** 業務テーブルの UPDATE と DELETE の入口を受け取る。 */
  public JooqOrderRepository(final TableWriter tableWriter) {
    this.tableWriter = tableWriter;
  }

  /** 版を比べずに注文を保存し、件数を返す。 */
  public int update(final Order order) {
    return tableWriter.updateWhere(order.id().value());
  }

  /** update と別の名前で、識別子だけを受け取るメソッドに書き込みを任せる。 */
  public int save(final Order order) {
    return writeColumns(order.id().value());
  }

  private int writeColumns(final String orderId) {
    return tableWriter.updateWhere(orderId);
  }
}
