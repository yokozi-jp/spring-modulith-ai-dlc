package archfixture.violating.ordering.infrastructure.persistence;

import archfixture.violating.ordering.domain.model.Order;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * 違反：aggregateRootInsertsGoThroughTableWriter（add のラムダの中で、集約ルートの INSERT を TableWriter.insert
 * を通さずに実行する）。
 *
 * <p>ArchUnit はラムダの本体の呼び出しを外側のメソッドの呼び出しとして数えるため、規則は {@code add} の違反として検出する。
 */
public final class JooqOrderLambdaDraftRepository {

  /** 注文のテーブル。 */
  private static final Table<Record> ORDERS = DSL.table(DSL.name("orders"));

  /** 注文 ID の列。 */
  private static final Field<String> ORDER_ID = DSL.field(DSL.name("order_id"), String.class);

  /** INSERT を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** jOOQ のコンテキストを受け取る。 */
  public JooqOrderLambdaDraftRepository(final DSLContext dsl) {
    this.dsl = dsl;
  }

  /** ラムダの中で集約ルートの INSERT を直接実行する。 */
  public Runnable add(final Order order) {
    return () -> dsl.insertInto(ORDERS).set(ORDER_ID, order.id().value()).execute();
  }
}
