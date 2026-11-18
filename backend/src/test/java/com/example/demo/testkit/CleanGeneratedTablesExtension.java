package com.example.demo.testkit;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.SHEDLOCK;

import com.example.demo.jooq.DefaultCatalog;
import com.example.demo.jooq.modulith.Modulith;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Schema;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 各テストの後に、jOOQ が生成したアプリケーションテーブルの行をすべて消す JUnit 拡張。
 *
 * <p>業務のスキーマは、アプリロールに権限がなく、モジュールのロールも別のモジュールの表を消せない（ADR-094）。そのため、表の所有者（マイグレーションロール）の接続で {@code
 * TRUNCATE} する。{@code TRUNCATE} は RLS の対象外で、モジュールとテナントの境界に左右されない。{@code modulith} スキーマの表は、アプリロールの
 * {@code DELETE} で消す。
 *
 * <p>生成対象は Liquibase 管理テーブル（{@code DATABASECHANGELOG} など）を codegen で除外済みなので、
 * マイグレーション状態を壊さずアプリのデータだけを消す。テーブルを追加して codegen を再生成すれば、 後始末の対象も自動で増える。コミットを伴う {@link
 * CommittedDatabaseTest} 用の後始末機構。
 *
 * <p>ShedLock の表（{@code modulith.shedlock}）は消さず、すべての行の期限を過去にする。ShedLock
 * はロックの行を作ったことをメモリに覚え、行を消すと同じ文脈では作り直さず、ロックを取れなくなるためである（ADR-079）。期限を過去にするのは、あるテストが取って {@code
 * lock-at-least-for} の間残したロックが、後に動く別のテストのクラスを待たせないためである。
 *
 * <p>消す前に、未完了のイベント出版（{@code PUBLISHED}、{@code PROCESSING}、{@code RESUBMITTED}）がなくなるまで待つ。非同期の
 * Listener はテストの本体が終わった後にコミットすることがあり、消した後に書いた決済記録や出版が次のテストに残るためである。{@code FAILED} は終わった状態なので待たない。
 * 待っても未完了の出版が残るときは、消してからテストを失敗させる。消さずに失敗すると、終わらない行が残り、後のすべてのテストの待ちが上限まで延び、後始末も止まるためである。 待ちが {@code
 * Error} で失敗したときも消す。消すことも失敗したときは、待ちの失敗を投げ、消すときの例外は {@code addSuppressed} で添える。
 */
public final class CleanGeneratedTablesExtension implements AfterEachCallback {

  /** イベントの出版の表と ShedLock の表を持つスキーマ。 */
  private static final Schema MODULITH = Modulith.MODULITH;

  /** 後始末の TRUNCATE が表のロックを待つ上限。 */
  private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(10);

  /** 未完了の出版がなくなるのを待つ上限。決済代行の最悪の時間（3 秒）より長くする。 */
  private static final Duration IN_FLIGHT_TIMEOUT = Duration.ofSeconds(10);

  // 待ちの失敗は ConditionTimeoutException も条件の評価の DB の例外も AssertionError などの Error もあり、
  // どれでも消してから元の失敗を投げるため、Throwable を受ける。投げ直しは型が保たれ、検査例外を宣言しない。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Override
  public void afterEach(final ExtensionContext context) {
    final DSLContext dslContext =
        SpringExtension.getApplicationContext(context).getBean(DSLContext.class);
    try {
      Awaitility.await("in-flight event publications")
          .atMost(IN_FLIGHT_TIMEOUT)
          .until(() ->
              dslContext.fetchCount(
                      EVENT_PUBLICATION,
                      EVENT_PUBLICATION.STATUS.in("PUBLISHED", "PROCESSING", "RESUBMITTED"))
                  == 0);
    } catch (final Throwable failure) {
      try {
        deleteAll(context, dslContext);
      } catch (final Throwable cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
    deleteAll(context, dslContext);
  }

  /** 業務のスキーマの表を所有者の接続で消し、{@code modulith} の ShedLock 以外の表の行を消し、ロックの期限を過去にする。 */
  private static void deleteAll(final ExtensionContext context, final DSLContext dslContext) {
    final Environment environment = SpringExtension.getApplicationContext(context).getEnvironment();
    final Properties properties = new Properties();
    properties.setProperty("user", environment.getRequiredProperty("MIGRATION_DB_USERNAME"));
    properties.setProperty("password", environment.getRequiredProperty("MIGRATION_DB_PASSWORD"));
    // TRUNCATE は表の排他ロックを取る。残った接続を無期限に待たず、テストを失敗させる。
    properties.setProperty("options", "-c lock_timeout=" + LOCK_TIMEOUT.toMillis());
    try (Connection owner =
        DriverManager.getConnection(
            environment.getRequiredProperty("spring.datasource.url"), properties)) {
      final DSLContext ownerDsl = DSL.using(owner, SQLDialect.POSTGRES);
      for (final Schema schema : DefaultCatalog.DEFAULT_CATALOG.getSchemas()) {
        for (final Table<?> table : schema.getTables()) {
          if (MODULITH.equals(schema)) {
            if (!SHEDLOCK.equals(table)) {
              dslContext.deleteFrom(table).execute();
            }
          } else {
            ownerDsl.truncate(table).execute();
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("テストの後始末で所有者の接続を使えない", e);
    }
    expireLocks(dslContext);
  }

  /**
   * ShedLock のすべてのロックの期限を、DB の時計によらない過去（{@link Instant#EPOCH}）にする。期限は DB の時刻で決まり、固定の時計では進まないため。
   *
   * <p>期限を {@code locked_at} にしない。NTP の同期で DB の壁時計が戻ると（WSL2 では数秒戻ることがある）、{@code locked_at} が DB
   * の今より後に残り、期限を過去にしたはずのロックを取れないためである。
   */
  public static void expireLocks(final DSLContext dsl) {
    dsl.update(SHEDLOCK).set(SHEDLOCK.LOCK_UNTIL, Instant.EPOCH).execute();
  }
}
