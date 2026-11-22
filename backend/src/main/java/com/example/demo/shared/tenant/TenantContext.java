package com.example.demo.shared.tenant;

import java.lang.ScopedValue.CallableOp;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * 呼び出しの間だけ、操作しているテナントを {@link ScopedValue} に束縛する。
 *
 * <p>リクエストの間は、セッションで選んだテナントを入口の filter が束縛する。リスナーの間は、イベントのテナントを {@code TenantListenerAspect}
 * が束縛する。束縛は呼び出しの終わりで外れ、スレッドにもプールの接続にも残らない。
 *
 * <p>束縛の間は、同じテナントを MDC の {@value #LOG_KEY} にも入れ、その間のログの毎行に付ける。
 *
 * <p>{@link #call} を呼んでよいのは、その filter と Aspect
 * だけである。ほかの層が呼ぶと、リクエストやイベントに無関係なテナントへ切り替えられるため、ArchUnit の {@code
 * tenantIsBoundOnlyByRequestFiltersAndListenerAspect} が止める。
 */
public final class TenantContext {

  /** ログとトレースでテナントの {@code public_id} を持つ項目の名前（docs/observability/conventions.md）。 */
  public static final String LOG_KEY = "tenant.id";

  /** 呼び出し中のテナント。 */
  private static final ScopedValue<TenantId> BOUND = ScopedValue.newInstance();

  private TenantContext() {}

  /**
   * テナントを束縛して処理を呼ぶ。束縛の中で別のテナントを束縛すると、その呼び出しの間は内側のテナントになる。
   *
   * @param tenantId 束縛するテナント
   * @param operation 束縛の中で呼ぶ処理
   * @return 処理の結果
   * @throws NullPointerException テナントが null の場合
   * @throws X 処理が投げた例外
   */
  public static <R extends @Nullable Object, X extends Throwable> R call(
      final TenantId tenantId, final CallableOp<? extends R, X> operation) throws X {
    final TenantId bound = Objects.requireNonNull(tenantId, "tenantId");
    MDC.put(LOG_KEY, bound.toString());
    try {
      return ScopedValue.where(BOUND, bound).call(operation);
    } finally {
      // 入れ子の束縛から戻ったときは、外側のテナントに戻す。
      if (BOUND.isBound()) {
        MDC.put(LOG_KEY, BOUND.get().toString());
      } else {
        MDC.remove(LOG_KEY);
      }
    }
  }

  /**
   * 呼び出し中のテナントを返す。
   *
   * @throws IllegalStateException テナントを束縛していない呼び出しの場合
   */
  public static TenantId current() {
    return BOUND.orElseThrow(() ->
        new IllegalStateException(
            "tenant is not bound: call from a request with a selected tenant or an @ApplicationModuleListener"));
  }

  /**
   * 呼び出し中のテナントを、束縛していなければ空で返す。
   *
   * <p>テナントのない呼び出し（Spring Modulith の内部、再投入、ShedLock）も通るトランザクションマネージャーのためにある。業務の処理は {@link
   * #current()} を使い、テナントがないことを「データがない」と取り違えない。
   */
  public static Optional<TenantId> findCurrent() {
    return BOUND.isBound() ? Optional.of(BOUND.get()) : Optional.empty();
  }
}
