package archfixture.violating.order.application;

import archfixture.violating.shared.infrastructure.persistence.CommonColumns;
import java.util.Map;

/** 違反：sharedModuleIsUsedOnlyByPersistenceAdapters（application から shared の共通処理を直接使う）。 */
public class OrderAuditColumns {

  /** application から使ってはいけない shared の共通処理。 */
  private final CommonColumns commonColumns;

  /** shared の共通処理を受け取る。 */
  public OrderAuditColumns(final CommonColumns commonColumns) {
    this.commonColumns = commonColumns;
  }

  /** 注文の共通カラムの値を返す。 */
  public Map<String, Object> values() {
    return commonColumns.forInsert();
  }
}
