package com.example.demo.shared.infrastructure.persistence;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * 共通カラムの {@code *_pgm_cd} の値を、ユースケースの呼び出しの間だけ {@link ScopedValue} に束縛する（ADR-051）。
 *
 * <p>機能モジュールの {@code *CommandHandler} の {@code handle} と、{@code *Listener} の public メソッドを対象にする。値は
 * {@code モジュール名.クラスの単純名から CommandHandler か Listener を除いた名前}（{@code order.PlaceOrder}）である。
 * 束縛の中で別のユースケースが呼ばれたら、その呼び出しの間は内側の値になる。
 *
 * <p>{@code *Listener} の呼び出しの間は、内側の CommandHandler の呼び出しも含めて、Listener の中であることも束縛する。{@link
 * CommonColumns} は、Listener の中の書き込みの {@code *_by} に、起点の利用者でなく {@code *_pgm_cd} と同じ値を登録する。
 */
@Aspect
@Component
public class PgmCdAspect {

  /** このクラスのパッケージの、ベースパッケージより後ろの部分。 */
  private static final String OWN_PACKAGE_SUFFIX = ".shared.infrastructure.persistence";

  /** モジュール名を切り出す基準のベースパッケージ（末尾に {@code .} を含む）。shared がルートパッケージの型に依存しないよう、自分のパッケージから導く。 */
  private static final String BASE_PACKAGE = basePackageOf(PgmCdAspect.class.getPackageName());

  /** 呼び出し中のユースケースの {@code *_pgm_cd}。 */
  /* package */ static final ScopedValue<String> PGM_CD = ScopedValue.newInstance();

  /** 呼び出し中のユースケースが、{@code *Listener} の呼び出しの中にあるか。 */
  private static final ScopedValue<Boolean> IN_LISTENER = ScopedValue.newInstance();

  /** 呼び出し中のユースケースの {@code *_pgm_cd} を、呼び出しの間だけ束縛する。 */
  @Around(
      "execution(* com.example.demo.*..*CommandHandler.handle(..))"
          + " || execution(public * com.example.demo.*..*Listener.*(..))")
  public @Nullable Object bindPgmCd(final ProceedingJoinPoint joinPoint) throws Throwable {
    final Class<?> useCase = joinPoint.getSignature().getDeclaringType();
    return ScopedValue.where(PGM_CD, pgmCd(useCase))
        .where(IN_LISTENER, inListener() || useCase.getSimpleName().endsWith("Listener"))
        .call(joinPoint::proceed);
  }

  /** 呼び出し中のユースケースが {@code *Listener} の呼び出しの中にあれば true を返す。束縛の外では false を返す。 */
  /* package */ static boolean inListener() {
    return IN_LISTENER.orElse(Boolean.FALSE);
  }

  /**
   * 呼び出し中のユースケースの {@code *_pgm_cd} を返す。
   *
   * @throws IllegalStateException CommandHandler と Listener の外で呼ばれた場合
   */
  /* package */ static String current() {
    return PGM_CD.orElseThrow(
        () ->
            new IllegalStateException(
                "pgm_cd is not bound: call from a *CommandHandler.handle or a *Listener method"));
  }

  /** ユースケースのクラスから {@code *_pgm_cd} を求める。 */
  /* package */ static String pgmCd(final Class<?> useCase) {
    final String relative = useCase.getName().substring(BASE_PACKAGE.length());
    final String simpleName =
        useCase.getSimpleName().replaceFirst("(CommandHandler|Listener)$", "");
    return relative.substring(0, relative.indexOf('.')) + "." + simpleName;
  }

  /**
   * このクラスのパッケージからベースパッケージを導く。末尾に {@code .} を付けて返す。
   *
   * @throws IllegalStateException パッケージが {@code <base>.shared.infrastructure.persistence}
   *     でない場合。クラスを移したときに起動で気付く
   */
  /* package */ static String basePackageOf(final String ownPackage) {
    if (!ownPackage.endsWith(OWN_PACKAGE_SUFFIX)) {
      throw new IllegalStateException(
          "PgmCdAspect must be in <base>" + OWN_PACKAGE_SUFFIX + ": package=" + ownPackage);
    }
    return ownPackage.substring(0, ownPackage.length() - OWN_PACKAGE_SUFFIX.length() + 1);
  }
}
