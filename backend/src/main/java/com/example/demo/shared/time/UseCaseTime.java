package com.example.demo.shared.time;

import java.lang.ScopedValue.CallableOp;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * 1 つのユースケースの現在時刻を、呼び出しの間だけ {@link ScopedValue} に束縛する（ADR-109）。
 *
 * <p>{@code PgmCdAspect} が一番外の CommandHandler か Listener の呼び出しで {@link #call} を呼び、時計を 1
 * 回だけ読む。業務の時刻と共通カラムの {@code *_at} は、{@link #now()} で同じ値を読む。
 */
public final class UseCaseTime {

  /** 呼び出し中のユースケースの現在時刻。 */
  private static final ScopedValue<Instant> BOUND = ScopedValue.newInstance();

  private UseCaseTime() {}

  /**
   * 現在時刻が束縛されていなければ時計を 1 回読んで束縛し、処理を呼ぶ。束縛されていれば読まずに引き継ぐ。
   *
   * <p>Listener の中で呼ぶ CommandHandler は、Listener の時刻を引き継ぐ。
   *
   * @param clock 束縛されていないときに読む時計
   * @param operation 束縛の中で呼ぶ処理
   * @return 処理の結果
   * @throws X 処理が投げた例外
   */
  public static <R extends @Nullable Object, X extends Throwable> R call(
      final Clock clock, final CallableOp<? extends R, X> operation) throws X {
    if (BOUND.isBound()) {
      return operation.call();
    }
    return ScopedValue.where(BOUND, Instant.now(clock)).call(operation);
  }

  /**
   * 呼び出し中のユースケースの現在時刻を返す。
   *
   * @throws IllegalStateException CommandHandler と Listener の外で呼ばれた場合
   */
  public static Instant now() {
    return BOUND.orElseThrow(() ->
        new IllegalStateException(
            "use case time is not bound: call from a *CommandHandler.handle or a *Listener method"));
  }
}
