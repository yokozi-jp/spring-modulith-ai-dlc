package com.example.demo.shared.infrastructure.persistence;

import com.example.demo.error.ResourceConflictException;
import com.example.demo.error.ResourceNotFoundException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 更新の直前に行をロックしてから {@code lock_no} を比較する楽観的ロックで、行を更新する。
 *
 * <p>手順は docs/database/postgresql-concurrency-control.md の「楽観的ロック」に従う。 {@code SELECT ... FOR UPDATE
 * NOWAIT} で行をロックし、保存された {@code lock_no} と画面などから受け取った値を比べ、一致したときだけ {@code lock_no} を 1 加算して更新する。jOOQ
 * の {@code executeWithOptimisticLocking} と {@code recordVersionFields} は使わない。
 */
@Component
public class OptimisticLock {

  /** PostgreSQL の lock_not_available。{@code NOWAIT} でロックを取れなかったときの SQLSTATE。 */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  /** ロックと更新を実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 更新のカラムと {@code lock_no} の加算を組み立てる。 */
  private final CommonColumns commonColumns;

  /** jOOQ のコンテキストと共通カラムの共通処理を受け取る。 */
  public OptimisticLock(final DSLContext dsl, final CommonColumns commonColumns) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
  }

  /**
   * 主キーで特定した 1 行を、楽観的ロックで更新する。
   *
   * @param table 更新先のテーブル
   * @param byPrimaryKey 主キーで 1 行を特定する条件
   * @param clientLockNo 画面などから受け取った {@code lock_no}
   * @param values 更新する業務のカラムと値。共通カラムは含めない
   * @param useCase 処理を実行するユースケースのクラス。{@code updated_pgm_cd} の値になる
   * @throws ResourceNotFoundException 行がない場合
   * @throws ResourceConflictException {@code lock_no} が一致しない場合、または他の処理が行をロックしている場合
   * @throws IllegalStateException トランザクションの外で呼ばれた場合
   */
  public <R extends Record> void update(
      final Table<R> table,
      final Condition byPrimaryKey,
      final long clientLockNo,
      final Map<? extends Field<?>, ?> values,
      final Class<?> useCase) {
    // 自動コミットでは SELECT ... FOR UPDATE のロックが文の終わりで外れ、更新を守れない。
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "optimistic lock requires an active transaction: table=" + table.getName());
    }
    final Field<Long> lockNo = CommonColumns.requiredField(table, "lock_no", Long.class);
    final long storedLockNo =
        lockRow(table, byPrimaryKey, lockNo)
            .orElseThrow(
                () -> new ResourceNotFoundException("row not found: table=" + table.getName()));
    if (storedLockNo != clientLockNo) {
      throw new ResourceConflictException("lock_no mismatch: table=" + table.getName());
    }
    dsl.update(table)
        .set(values)
        .set(commonColumns.forUpdate(table, useCase))
        .where(byPrimaryKey)
        .execute();
  }

  private Optional<Long> lockRow(
      final Table<?> table, final Condition byPrimaryKey, final Field<Long> lockNo) {
    try {
      return dsl.select(lockNo)
          .from(table)
          .where(byPrimaryKey)
          .forUpdate()
          .noWait()
          .fetchOptional(lockNo);
    } catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
      // Spring の例外へ変換できなかったエラーは、jOOQ の例外のまま届く。
      if (isLockNotAvailable(exception)) {
        throw new ResourceConflictException(
            "lock not available: table=" + table.getName(), exception);
      }
      throw exception;
    }
  }

  private static boolean isLockNotAvailable(final Throwable exception) {
    for (@Nullable Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException
          && LOCK_NOT_AVAILABLE.equals(sqlException.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
