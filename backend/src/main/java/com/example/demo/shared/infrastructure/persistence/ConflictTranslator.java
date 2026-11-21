package com.example.demo.shared.infrastructure.persistence;

import com.example.demo.shared.concurrency.ConflictException;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/**
 * 書き込みの文の例外を、SQLSTATE で {@link ConflictException} に変える（ADR-054、ADR-062）。
 *
 * <p>{@link TableWriter} とその子の書き込みが、すべての文をここで実行する。
 */
/* package */ final class ConflictTranslator {

  /** 行ロックを取れなかったときの SQLSTATE。 */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  /** 一意制約に違反したときの SQLSTATE。 */
  private static final String UNIQUE_VIOLATION = "23505";

  private ConflictTranslator() {}

  /**
   * 文を実行し、{@code 55P03} を種類 {@code LOCK}、{@code 23505} を種類 {@code UNIQUE} の {@link
   * ConflictException} に変える。原因は付けない。
   *
   * <p>Spring が同じ型の例外にまとめる別の SQLSTATE（{@code 21000} など）は、変換せずに投げる。
   */
  // 原因の例外のメッセージに SQL と重複したキーの値が入るため、原因を付けない（ADR-062、docs/observability/conventions.md）。
  @SuppressWarnings({"PMD.PreserveStackTrace", "UnusedException"})
  /* package */ static <T> T executeOrConflict(
      final Supplier<T> statement, final Supplier<String> target) {
    try {
      return statement.get();
    } catch (final DataAccessException e) {
      final String sqlState = sqlStateOf(e);
      if (LOCK_NOT_AVAILABLE.equals(sqlState)) {
        throw new ConflictException(
            ConflictException.Kind.LOCK, "row is locked by another request: " + target.get());
      }
      if (UNIQUE_VIOLATION.equals(sqlState)) {
        throw new ConflictException(
            ConflictException.Kind.UNIQUE, "unique key already exists: " + target.get());
      }
      throw e;
    }
  }

  /** 例外の原因をたどり、最初の {@link SQLException} の SQLSTATE を返す。なければ空文字を返す。 */
  private static String sqlStateOf(final Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return "";
  }
}
