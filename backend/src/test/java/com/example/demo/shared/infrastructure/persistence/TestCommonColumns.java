package com.example.demo.shared.infrastructure.persistence;

import com.example.demo.shared.time.UseCaseTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Spring の外で共通カラムを登録するテストが使う補助。
 *
 * <p>{@link CommonColumns} は trace、{@code *_pgm_cd}、ユースケースの現在時刻の束縛がないと登録を失敗させる。trace は {@link
 * TracerStubs} で固定し、{@code *_pgm_cd} と現在時刻は {@link #runAs} と {@link #callAs} の間だけ束縛する。現在時刻は {@link
 * UseCaseTime#call} で束縛するため、すでに束縛された呼び出しの中では外側の時刻を引き継ぐ。
 */
// テストの補助であり、テストケースを持たない。
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestCommonColumns {

  /** テストで登録する trace ID。 */
  public static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

  /** テストで登録する {@code *_pgm_cd}。 */
  public static final String PGM_CD = "test.Fixture";

  private TestCommonColumns() {}

  /** trace ID を {@link #TRACE_ID} にした共通処理を返す。 */
  public static CommonColumns create() {
    return new CommonColumns(TracerStubs.withTraceId(TRACE_ID));
  }

  /** {@code *_pgm_cd} を {@link #PGM_CD} に、現在時刻を {@code at} に束縛して処理を呼び、結果を返す。 */
  public static <T> T callAs(final Instant at, final Supplier<T> action) {
    return UseCaseTime.call(
        fixed(at), () -> ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).call(action::get));
  }

  /** {@code *_pgm_cd} を {@link #PGM_CD} に、現在時刻を {@code at} に束縛して処理を呼ぶ。 */
  public static void runAs(final Instant at, final Runnable action) {
    runAs(PGM_CD, at, action);
  }

  /** {@code *_pgm_cd} を {@code pgmCd} に、現在時刻を {@code at} に束縛して処理を呼ぶ。 */
  public static void runAs(final String pgmCd, final Instant at, final Runnable action) {
    UseCaseTime.<@Nullable Object, RuntimeException>call(
        fixed(at),
        () -> {
          ScopedValue.where(PgmCdAspect.PGM_CD, pgmCd).run(action);
          return null;
        });
  }

  private static Clock fixed(final Instant at) {
    return Clock.fixed(at, ZoneOffset.UTC);
  }
}
