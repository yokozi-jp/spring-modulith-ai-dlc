package com.example.demo.shared.infrastructure.persistence;

import org.jooq.impl.AbstractConverter;
import org.jspecify.annotations.Nullable;

/**
 * NOT NULL の文字列カラムで、NULL を空文字 {@code ''} へそろえる jOOQ の Converter。
 *
 * <p>docs/database/postgresql-data-types.md の「文字列の既定値」に従い、NULL から空文字への変換をカラムごとに書かず一律に行う。
 *
 * <p>コード生成の forcedType で NOT NULL の {@code varchar} に当て、文字列の共通カラムと {@code patched_*} には当てない。
 */
public final class NullToEmptyStringConverter extends AbstractConverter<String, String> {

  private static final long serialVersionUID = 1L;

  /** コード生成が {@code new NullToEmptyStringConverter()} で生成する。 */
  public NullToEmptyStringConverter() {
    super(String.class, String.class);
  }

  /** DB から読んだ NULL を空文字にする。 */
  @Override
  public String from(final @Nullable String databaseObject) {
    return databaseObject == null ? "" : databaseObject;
  }

  /** DB へ書く NULL を空文字にする。 */
  @Override
  @SuppressWarnings("PMD.ShortMethodName") // jOOQ の Converter が定めるメソッド名のため変えられない。
  public String to(final @Nullable String userObject) {
    return userObject == null ? "" : userObject;
  }
}
