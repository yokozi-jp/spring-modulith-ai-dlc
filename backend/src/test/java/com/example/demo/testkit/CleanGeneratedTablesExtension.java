package com.example.demo.testkit;

import static com.example.demo.jooq.modulith.Tables.EVENT_PUBLICATION;
import static com.example.demo.jooq.modulith.Tables.SHEDLOCK;

import com.example.demo.jooq.DefaultCatalog;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.Schema;
import org.jooq.Table;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * 各テストの後に、jOOQ が生成したアプリケーションテーブルの行をすべて {@code DELETE} する JUnit 拡張。
 *
 * <p>後始末はアプリケーションと同じ DML 限定ロールで実行するため、{@code TRUNCATE} ではなく {@code DELETE} を使う。本番のアプリケーションロールは
 * {@code CREATE}/{@code ALTER}/{@code DROP} も {@code TRUNCATE}
 * も持たない（ADR-011）。後始末が本番で実行できない権限に依存しないようにし、テストと本番の権限を揃える。
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
 * 待っても未完了の出版が残るときは、消してからテストを失敗させる。消さずに失敗すると、終わらない行が残り、後のすべてのテストの待ちが上限まで延び、後始末も止まるためである。
 * 消すことも失敗したときは、待ちの失敗を投げ、消すときの例外は {@code addSuppressed} で添える。
 */
public final class CleanGeneratedTablesExtension implements AfterEachCallback {

  /** 未完了の出版がなくなるのを待つ上限。決済代行の最悪の時間（3 秒）より長くする。 */
  private static final Duration IN_FLIGHT_TIMEOUT = Duration.ofSeconds(10);

  // 待ちの失敗は ConditionTimeoutException も条件の評価の DB の例外もあり、どれでも消してから元の失敗を投げるため、実行時の例外を受ける。
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @Override
  public void afterEach(final ExtensionContext context) {
    final DSLContext dslContext =
        SpringExtension.getApplicationContext(context).getBean(DSLContext.class);
    try {
      Awaitility.await("in-flight event publications")
          .atMost(IN_FLIGHT_TIMEOUT)
          .until(
              () ->
                  dslContext.fetchCount(
                          EVENT_PUBLICATION,
                          EVENT_PUBLICATION.STATUS.in("PUBLISHED", "PROCESSING", "RESUBMITTED"))
                      == 0);
    } catch (final RuntimeException failure) {
      try {
        deleteAll(dslContext);
      } catch (final RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
    deleteAll(dslContext);
  }

  /** ShedLock の表のほかの生成したテーブルの行を消し、ロックの期限を過去にする。 */
  private static void deleteAll(final DSLContext dslContext) {
    for (final Schema schema : DefaultCatalog.DEFAULT_CATALOG.getSchemas()) {
      for (final Table<?> table : schema.getTables()) {
        if (SHEDLOCK.equals(table)) {
          continue;
        }
        dslContext.deleteFrom(table).execute();
      }
    }
    expireLocks(dslContext);
  }

  /** ShedLock のすべてのロックの期限を過去にする。期限は DB の時刻で決まり、固定の時計では進まないため。 */
  public static void expireLocks(final DSLContext dsl) {
    dsl.update(SHEDLOCK).set(SHEDLOCK.LOCK_UNTIL, SHEDLOCK.LOCKED_AT).execute();
  }
}
