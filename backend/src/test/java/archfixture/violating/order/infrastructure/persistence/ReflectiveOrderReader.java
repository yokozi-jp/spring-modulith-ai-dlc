package archfixture.violating.order.infrastructure.persistence;

import archfixture.violating.order.domain.model.OrderId;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultRecordMapper;

/**
 * 違反：jooqReflectionMappingIsNotUsed（メソッドごとに、名前のリフレクションで対応づける jOOQ のメソッドを一つ呼ぶ）と
 * mappingLibrariesAreNotUsed（{@code DefaultRecordMapper} を使う）。
 */
public final class ReflectiveOrderReader {

  /** 注文のテーブル。 */
  private static final Table<Record> ORDERS = DSL.table(DSL.name("orders"));

  private ReflectiveOrderReader() {}

  /** {@code Record.into(Class)} で対応づける。 */
  public static OrderId recordInto(final Record row) {
    return row.into(OrderId.class);
  }

  /** {@code Result.into(Class)} で対応づける。 */
  public static List<OrderId> resultInto(final Result<Record> rows) {
    return rows.into(OrderId.class);
  }

  /** {@code ResultQuery.fetchInto(Class)} で対応づける。 */
  public static List<OrderId> fetchInto(final DSLContext dsl) {
    return dsl.selectFrom(ORDERS).fetchInto(OrderId.class);
  }

  /** {@code ResultQuery.fetchOneInto(Class)} で対応づける。 */
  public static OrderId fetchOneInto(final DSLContext dsl) {
    return dsl.selectFrom(ORDERS).fetchOneInto(OrderId.class);
  }

  /** {@code ResultQuery.fetchOptionalInto(Class)} で対応づける。 */
  public static Optional<OrderId> fetchOptionalInto(final DSLContext dsl) {
    return dsl.selectFrom(ORDERS).fetchOptionalInto(OrderId.class);
  }

  /** {@code ResultQuery.fetchSingleInto(Class)} で対応づける。 */
  public static OrderId fetchSingleInto(final DSLContext dsl) {
    return dsl.selectFrom(ORDERS).fetchSingleInto(OrderId.class);
  }

  /** {@code DefaultRecordMapper} で対応づける。 */
  public static List<OrderId> defaultRecordMapper(final Result<Record> rows) {
    return rows.map(new DefaultRecordMapper<>(rows.recordType(), OrderId.class));
  }
}
