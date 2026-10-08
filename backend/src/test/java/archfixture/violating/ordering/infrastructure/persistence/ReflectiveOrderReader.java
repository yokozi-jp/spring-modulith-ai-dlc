package archfixture.violating.ordering.infrastructure.persistence;

import archfixture.violating.ordering.domain.model.OrderId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.RecordUnmapper;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultRecordMapper;
import org.jooq.impl.DefaultRecordUnmapper;

/**
 * 違反：jooqReflectionMappingIsNotUsed（メソッドごとに、名前のリフレクションで対応づける jOOQ のメソッドを一つ呼ぶ）と
 * mappingLibrariesAreNotUsed（{@code DefaultRecordMapper} と {@code DefaultRecordUnmapper} を使う）。
 */
// 規則が検出する呼び出しを一つずつ確かめるため、禁止するメソッドごとに一つのメソッドを置く。
@SuppressWarnings("PMD.TooManyMethods")
public final class ReflectiveOrderReader {

  /** 注文のテーブル。 */
  private static final Table<Record> ORDERS = DSL.table(DSL.name("orders"));

  /** 注文 ID の列。 */
  private static final Field<String> ORDER_ID = DSL.field(DSL.name("order_id"), String.class);

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

  /** {@code Result.intoMap(Field, Class)} で対応づける。 */
  public static Map<String, OrderId> intoMap(final Result<Record> rows) {
    return rows.intoMap(ORDER_ID, OrderId.class);
  }

  /** {@code Result.intoGroups(Field, Class)} で対応づける。 */
  public static Map<String, List<OrderId>> intoGroups(final Result<Record> rows) {
    return rows.intoGroups(ORDER_ID, OrderId.class);
  }

  /** {@code ResultQuery.fetchMap(Field, Class)} で対応づける。 */
  public static Map<String, OrderId> fetchMap(final DSLContext dsl) {
    return dsl.selectFrom(ORDERS).fetchMap(ORDER_ID, OrderId.class);
  }

  /** {@code ResultQuery.fetchGroups(Field, Class)} で対応づける。 */
  public static Map<String, List<OrderId>> fetchGroups(final DSLContext dsl) {
    return dsl.selectFrom(ORDERS).fetchGroups(ORDER_ID, OrderId.class);
  }

  /** {@code Record.into(Object)} で、既存のオブジェクトへ対応づける。 */
  public static OrderId recordIntoObject(final Record row, final OrderId target) {
    return row.into(target);
  }

  /** {@code Record.from(Object)} で、オブジェクトから書き込む。 */
  public static void recordFrom(final Record row, final OrderId source) {
    row.from(source);
  }

  /** {@code DSLContext.newRecord(Table, Object)} で、オブジェクトから書き込む。 */
  public static Record newRecordFromObject(final DSLContext dsl, final OrderId source) {
    return dsl.newRecord(ORDERS, source);
  }

  /** {@code DefaultRecordUnmapper} で、オブジェクトから書き込む。 */
  public static RecordUnmapper<OrderId, Record> defaultRecordUnmapper(
      final Result<Record> rows, final Configuration configuration) {
    return new DefaultRecordUnmapper<>(OrderId.class, rows.recordType(), configuration);
  }
}
