package archfixture.violating.ordering.infrastructure.persistence;

import com.example.demo.jooq.product.Tables;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 違反：ownGeneratedSchemaRule、moduleCodeDoesNotUseJdbcRule、moduleCodeDoesNotChangeConnectionSettingsRule。
 *
 * <p>別のモジュールのスキーマの生成クラスを使い、JDBC と接続の設定の API でモジュールのロールを迂回する。規則がバイトコードを読むだけで、実行しない。
 */
public final class RoleBypassingOrderAccess {

  private RoleBypassingOrderAccess() {}

  /** 別のモジュール（product）のスキーマの生成クラスを使う。 */
  public static int otherModuleSchema(final DSLContext dsl) {
    return dsl.fetchCount(Tables.M_PRODUCT);
  }

  /** {@code JdbcTemplate} で読む。 */
  public static Integer jdbcTemplateQuery(final JdbcTemplate jdbcTemplate) {
    return jdbcTemplate.queryForObject("select 1", Integer.class);
  }

  /** {@code DataSource.getConnection} を呼ぶ。 */
  public static Connection rawConnection(final DataSource dataSource) throws SQLException {
    return dataSource.getConnection();
  }

  /** {@code DSLContext.set} を呼ぶ。 */
  public static Object dslSet(final DSLContext dsl) {
    return dsl.set("role", DSL.inline("ordering_module"));
  }

  /** {@code DSLContext.setLocal} を呼ぶ。 */
  public static Object dslSetLocal(final DSLContext dsl) {
    return dsl.setLocal("role", DSL.inline("ordering_module"));
  }

  /** {@code DSL.setLocal} で作った文を実行する。 */
  public static int staticSetLocal(final DSLContext dsl) {
    return dsl.execute(DSL.setLocal("role", DSL.inline("product_module")));
  }

  /** {@code DSLContext.setSchema} を呼ぶ。 */
  public static Object dslSetSchema(final DSLContext dsl) {
    return dsl.setSchema("product");
  }

  /** {@code DSLContext.connectionResult} を呼ぶ。 */
  public static String dslConnectionResult(final DSLContext dsl) {
    return dsl.connectionResult(connection -> "connected");
  }
}
