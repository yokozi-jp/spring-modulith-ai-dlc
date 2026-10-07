package com.example.demo.shared.infrastructure.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * 業務機能のテストが、Spring の外で共通カラムを登録するための補助。
 *
 * <p>{@link CommonColumns} は trace と {@code *_pgm_cd} の束縛がないと登録を失敗させる。trace は {@link TracerStubs}
 * で固定し、{@code *_pgm_cd} は {@link #callAs} の間だけ束縛する。
 */
// テストの補助であり、テストケースを持たない。at は「その時刻の共通処理」と読ませるため短い名前にする。
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.ShortMethodName"})
public final class TestCommonColumns {

  /** テストで登録する trace ID。 */
  public static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

  /** テストで登録する {@code *_pgm_cd}。 */
  public static final String PGM_CD = "test.Fixture";

  private TestCommonColumns() {}

  /** 現在時刻を {@code instant} に固定し、trace ID を {@link #TRACE_ID} にした共通処理を返す。 */
  public static CommonColumns at(final Instant instant) {
    return new CommonColumns(
        Clock.fixed(instant, ZoneOffset.UTC), TracerStubs.withTraceId(TRACE_ID));
  }

  /** {@code *_pgm_cd} を {@link #PGM_CD} に束縛して処理を呼び、結果を返す。 */
  public static <T> T callAs(final Supplier<T> action) {
    return ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).call(action::get);
  }

  /** {@code *_pgm_cd} を {@link #PGM_CD} に束縛して処理を呼ぶ。 */
  public static void runAs(final Runnable action) {
    runAs(PGM_CD, action);
  }

  /** {@code *_pgm_cd} を {@code pgmCd} に束縛して処理を呼ぶ。 */
  public static void runAs(final String pgmCd, final Runnable action) {
    ScopedValue.where(PgmCdAspect.PGM_CD, pgmCd).run(action);
  }
}
