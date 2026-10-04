package archfixture.violating.order.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.jooq.Batch;
import org.jooq.ConnectionProvider;
import org.jooq.DAO;
import org.jooq.DSLContext;
import org.jooq.Delete;
import org.jooq.Field;
import org.jooq.InsertOnDuplicateStep;
import org.jooq.InsertQuery;
import org.jooq.LoaderOptionsStep;
import org.jooq.Merge;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableRecord;
import org.jooq.UpdatableRecord;
import org.jooq.Update;
import org.jooq.UpdateSetMoreStep;
import org.jooq.WithStep;
import org.jooq.impl.DSL;
import org.jooq.impl.QOM;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.init.DatabaseInitializationSettings;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.object.SqlUpdate;

/**
 * 違反：tableWritesGoThroughTableWriter（メソッドごとに、TableWriter を通さない書き込みの API を一つ使う）。
 *
 * <p>規則がバイトコードを読むだけで、実行しない。
 */
// 規則が検出する呼び出しを一つずつ確かめるため、禁止する API ごとに一つのメソッドを置く。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects", "PMD.ExcessivePublicCount"})
public final class DirectOrderWriter {

  /** 注文のテーブル。 */
  private static final Table<Record> ORDERS = DSL.table(DSL.name("orders"));

  /** 注文 ID の列。 */
  private static final Field<String> ORDER_ID = DSL.field(DSL.name("order_id"), String.class);

  /** 書き込みの SQL。 */
  private static final String DELETE_SQL = "delete from orders";

  private DirectOrderWriter() {}

  /** {@code DSLContext.update} を呼ぶ。 */
  public static Object dslUpdate(final DSLContext dsl) {
    return dsl.update(ORDERS);
  }

  /** {@code DSLContext.delete} を呼ぶ。 */
  public static Object dslDelete(final DSLContext dsl) {
    return dsl.delete(ORDERS);
  }

  /** {@code DSLContext.deleteFrom} を呼ぶ。 */
  public static Object dslDeleteFrom(final DSLContext dsl) {
    return dsl.deleteFrom(ORDERS);
  }

  /** {@code DSLContext.mergeInto} を呼ぶ。 */
  public static Object dslMergeInto(final DSLContext dsl) {
    return dsl.mergeInto(ORDERS);
  }

  /** {@code DSLContext.batchUpdate} を呼ぶ。 */
  public static Batch dslBatchUpdate(final DSLContext dsl, final UpdatableRecord<?> row) {
    return dsl.batchUpdate(row);
  }

  /** {@code DSLContext.batchStore} を呼ぶ。 */
  public static Batch dslBatchStore(final DSLContext dsl, final UpdatableRecord<?> row) {
    return dsl.batchStore(row);
  }

  /** {@code DSLContext.batchDelete} を呼ぶ。 */
  public static Batch dslBatchDelete(final DSLContext dsl, final UpdatableRecord<?> row) {
    return dsl.batchDelete(row);
  }

  /** {@code DSLContext.batchMerge} を呼ぶ。 */
  public static Batch dslBatchMerge(final DSLContext dsl, final UpdatableRecord<?> row) {
    return dsl.batchMerge(row);
  }

  /** {@code DSLContext.executeUpdate} を呼ぶ。 */
  public static <R extends UpdatableRecord<R>> int dslExecuteUpdate(
      final DSLContext dsl, final R row) {
    return dsl.executeUpdate(row);
  }

  /** {@code DSLContext.executeDelete} を呼ぶ。 */
  public static <R extends UpdatableRecord<R>> int dslExecuteDelete(
      final DSLContext dsl, final R row) {
    return dsl.executeDelete(row);
  }

  /** {@code DSLContext.connection} を呼ぶ。 */
  public static DSLContext dslConnection(final DSLContext dsl) {
    dsl.connection(connection -> connection.setReadOnly(false));
    return dsl;
  }

  /** {@code DSLContext.connectionResult} を呼ぶ。 */
  public static String dslConnectionResult(final DSLContext dsl) {
    return dsl.connectionResult(connection -> "connected");
  }

  /** {@code DSLContext.updateQuery} で作った UPDATE を {@code batch} で実行する。 */
  public static int[] dslUpdateQuery(final DSLContext dsl) {
    return dsl.batch(dsl.updateQuery(ORDERS)).execute();
  }

  /** {@code DSLContext.deleteQuery} を呼ぶ。 */
  public static Object dslDeleteQuery(final DSLContext dsl) {
    return dsl.deleteQuery(ORDERS);
  }

  /** {@code WithStep.update} を呼ぶ。 */
  public static Object withUpdate(final WithStep with) {
    return with.update(ORDERS);
  }

  /** {@code InsertQuery.onDuplicateKeyUpdate} を呼ぶ。 */
  public static InsertQuery<Record> insertQueryOnDuplicateKeyUpdate(
      final InsertQuery<Record> insert) {
    insert.onDuplicateKeyUpdate(true);
    return insert;
  }

  /** {@code InsertQuery.addValueForUpdate} を呼ぶ。 */
  public static InsertQuery<Record> insertQueryAddValueForUpdate(final InsertQuery<Record> insert) {
    insert.addValueForUpdate(ORDER_ID, "O-1");
    return insert;
  }

  /** {@code LoaderOptionsStep.onDuplicateKeyUpdate} を呼ぶ。 */
  public static Object loaderOnDuplicateKeyUpdate(final LoaderOptionsStep<Record> loader) {
    return loader.onDuplicateKeyUpdate();
  }

  /** 問い合わせのモデルの {@code QOM.Insert.$onDuplicateKeyUpdate} で、INSERT を UPSERT に組み替える。 */
  public static Object qomOnDuplicateKeyUpdate(final QOM.Insert<Record> insert) {
    return insert.$onDuplicateKeyUpdate(true);
  }

  /** Spring の {@code ResourceDatabasePopulator} で、接続を取らずに {@code DataSource} から SQL を流す。 */
  public static DataSource springScriptPopulator(final DataSource dataSource) {
    new ResourceDatabasePopulator(
            new ByteArrayResource(DELETE_SQL.getBytes(StandardCharsets.UTF_8)))
        .execute(dataSource);
    return dataSource;
  }

  /**
   * Spring Boot の {@code DataSourceScriptDatabaseInitializer} で、{@code DataSource} から SQL
   * のスクリプトを流す。
   */
  public static boolean bootScriptInitializer(final DataSource dataSource) {
    final DatabaseInitializationSettings settings = new DatabaseInitializationSettings();
    settings.setDataLocations(List.of("classpath:fix-orders.sql"));
    return new DataSourceScriptDatabaseInitializer(dataSource, settings).initializeDatabase();
  }

  /** Spring の {@code SqlUpdate} で、接続を取らずに {@code DataSource} から書く。 */
  public static int springSqlUpdate(final DataSource dataSource) {
    return new SqlUpdate(dataSource, DELETE_SQL).update();
  }

  /** {@code DSL.update} で接続のない UPDATE を作る。 */
  public static Object staticDslUpdate() {
    return DSL.update(ORDERS);
  }

  /** ラムダの中で {@code UpdateSetMoreStep.execute} を呼ぶ。 */
  public static UnaryOperator<UpdateSetMoreStep<Record>> lambdaExecute() {
    return set -> {
      set.execute();
      return set;
    };
  }

  /** {@code Update} の変数に代入してから {@code execute} を呼ぶ。 */
  public static int updateVariableExecute(final Update<?> update) {
    return update.execute();
  }

  /** {@code Update::execute} をメソッド参照する。 */
  public static ToIntFunction<Update<?>> updateMethodReference() {
    return Update::execute;
  }

  /** {@code UpdateSetMoreStep.returning} を呼ぶ。 */
  public static Object updateReturning(final UpdateSetMoreStep<Record> update) {
    return update.returning();
  }

  /** {@code Delete.execute} を呼ぶ。 */
  public static int deleteExecute(final Delete<?> delete) {
    return delete.execute();
  }

  /** {@code Merge.execute} を呼ぶ。 */
  public static int mergeExecute(final Merge<?> merge) {
    return merge.execute();
  }

  /** INSERT の途中の段で {@code onConflict} を呼ぶ。 */
  public static Object upsertOnConflict(final InsertOnDuplicateStep<Record> insert) {
    return insert.onConflict(ORDER_ID);
  }

  /** INSERT の途中の段で {@code onConflictOnConstraint} を呼ぶ。 */
  public static Object upsertOnConflictOnConstraint(final InsertOnDuplicateStep<Record> insert) {
    return insert.onConflictOnConstraint(DSL.name("orders_pkey"));
  }

  /** INSERT の途中の段で {@code onDuplicateKeyUpdate} を呼ぶ。 */
  public static Object upsertOnDuplicateKeyUpdate(final InsertOnDuplicateStep<Record> insert) {
    return insert.onDuplicateKeyUpdate();
  }

  /** {@code UpdatableRecord.store} を呼ぶ。 */
  public static int recordStore(final UpdatableRecord<?> row) {
    return row.store();
  }

  /** {@code UpdatableRecord.update} を呼ぶ。 */
  public static int recordUpdate(final UpdatableRecord<?> row) {
    return row.update();
  }

  /** {@code UpdatableRecord.delete} を呼ぶ。 */
  public static int recordDelete(final UpdatableRecord<?> row) {
    return row.delete();
  }

  /** {@code UpdatableRecord.merge} を呼ぶ。 */
  public static int recordMerge(final UpdatableRecord<?> row) {
    return row.merge();
  }

  /** {@code DAO.update} を呼ぶ。 */
  public static <R extends TableRecord<R>> String daoUpdate(
      final DAO<R, String, String> dao, final String order) {
    dao.update(order);
    return order;
  }

  /** {@code DAO.delete} を呼ぶ。 */
  public static <R extends TableRecord<R>> String daoDelete(
      final DAO<R, String, String> dao, final String order) {
    dao.delete(order);
    return order;
  }

  /** {@code DAO.deleteById} を呼ぶ。 */
  public static <R extends TableRecord<R>> String daoDeleteById(
      final DAO<R, String, String> dao, final String orderId) {
    dao.deleteById(orderId);
    return orderId;
  }

  /** {@code DAO.merge} を呼ぶ。 */
  public static <R extends TableRecord<R>> String daoMerge(
      final DAO<R, String, String> dao, final String order) {
    dao.merge(order);
    return order;
  }

  /** Spring の {@code JdbcTemplate.update} を呼ぶ。 */
  public static int jdbcTemplate(final JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.update(DELETE_SQL);
  }

  /** {@code DataSource.getConnection} を呼ぶ。 */
  public static Connection dataSourceConnection(final DataSource dataSource) throws SQLException {
    return dataSource.getConnection();
  }

  /** {@code Connection.prepareStatement} を呼ぶ。 */
  public static PreparedStatement jdbcConnection(final Connection connection) throws SQLException {
    return connection.prepareStatement(DELETE_SQL);
  }

  /** {@code Statement.executeUpdate} を呼ぶ。 */
  public static int jdbcStatement(final Statement statement) throws SQLException {
    return statement.executeUpdate(DELETE_SQL);
  }

  /** {@code ConnectionProvider.acquire} を呼ぶ。 */
  public static Connection connectionProvider(final ConnectionProvider provider) {
    return provider.acquire();
  }
}
