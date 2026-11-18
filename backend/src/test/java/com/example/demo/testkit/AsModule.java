package com.example.demo.testkit;

import com.example.demo.shared.infrastructure.persistence.ModuleRoles;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Table;

/**
 * テストの DB の読み書きを、トランザクションの間だけモジュールのロールで実行する（ADR-090）。
 *
 * <p>アプリロールは業務のスキーマに権限を持たない。本番はトランザクションの名前からモジュールのロールへ切り替えるが、トランザクションの名前を持たないテストの補助（商品の登録、行の読み戻し）は、同じ
 * {@code set_config('role', ?, true)} で切り替える。切り替えは呼び出しの間だけで、正常に終わったら元のロールへ戻す。本体が失敗したときは、jOOQ
 * がセーブポイントまで戻すため、切り替えも戻る。テストのトランザクションの中でも、アプリロールのままの接続でも使える。
 */
public final class AsModule {

  private AsModule() {}

  /** 表を持つモジュールの名前を返す。モジュールの名前はスキーマ名と同じである。 */
  public static String moduleOf(final Table<?> table) {
    return Objects.requireNonNull(table.getSchema(), "schema").getName();
  }

  /**
   * モジュールのロールで本体を実行し、結果を返す。
   *
   * @param dsl jOOQ のコンテキスト
   * @param module 切り替えるモジュールの名前
   * @param body モジュールのロールで動かす処理。引数はそのトランザクションの jOOQ のコンテキスト
   * @return 本体の結果
   */
  public static <T> T call(
      final DSLContext dsl, final String module, final Function<DSLContext, T> body) {
    return dsl.transactionResult(configuration -> {
      final DSLContext tx = configuration.dsl();
      final String previous = String.valueOf(tx.fetchValue("select current_setting('role')"));
      tx.fetch("select set_config('role', ?, true)", ModuleRoles.roleOf(module));
      final T result = body.apply(tx);
      tx.fetch("select set_config('role', ?, true)", previous);
      return result;
    });
  }

  /** モジュールのロールで本体を実行する。 */
  public static void run(
      final DSLContext dsl, final String module, final Consumer<DSLContext> body) {
    call(
        dsl,
        module,
        tx -> {
          body.accept(tx);
          return null;
        });
  }
}
