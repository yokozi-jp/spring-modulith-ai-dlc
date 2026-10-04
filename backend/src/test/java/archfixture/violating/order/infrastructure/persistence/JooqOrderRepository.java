package archfixture.violating.order.infrastructure.persistence;

import archfixture.violating.order.domain.model.Order;
import archfixture.violating.shared.infrastructure.persistence.TableWriter;

/**
 * 違反：repositoryUpdateAndDeleteCheckVersion（update が updateCheckingVersion を呼ばない）と
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
}
