package archfixture.violating.ordering.application;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** 違反：databaseTechnologyApisAreOnlyUsedByPersistenceAdapters（application から jOOQ を直接使う）。 */
public class JooqOrderCounter {

  /** application から使ってはいけない jOOQ の入口。 */
  private final DSLContext dsl;

  /** jOOQ の入口を受け取る。 */
  public JooqOrderCounter(final DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 注文の件数を返す。 */
  public int count() {
    return dsl.fetchCount(DSL.table(DSL.name("orders")));
  }
}
