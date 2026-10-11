package archfixture.violating.ordering.infrastructure.persistence;

import archfixture.violating.ordering.domain.model.Order;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * 違反：aggregateRootInsertsGoThroughTableWriter（add が集約ルートの INSERT を TableWriter.insert を通さずに実行する）。
 *
 * <p>規則がバイトコードを読むだけで、実行しない。
 */
public final class JooqOrderDraftRepository {

  /** 注文のテーブル。 */
  private static final Table<Record> ORDERS = DSL.table(DSL.name("orders"));

  /** 注文 ID の列。 */
  private static final Field<String> ORDER_ID = DSL.field(DSL.name("order_id"), String.class);

  /** INSERT を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** jOOQ のコンテキストを受け取る。 */
  public JooqOrderDraftRepository(final DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 集約ルートの INSERT を直接実行し、一意制約の違反を 409 に変えない。 */
  public void add(final Order order) {
    dsl.insertInto(ORDERS).set(ORDER_ID, order.id().value()).execute();
  }
}
