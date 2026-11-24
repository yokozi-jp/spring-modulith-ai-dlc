package com.example.demo.tenancy.infrastructure.client;

import java.util.function.Predicate;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.http.HttpStatusFamily;

/**
 * メールの送信の circuit breaker が失敗に数えない例外を選ぶ。スロットリング以外の 4xx の {@link SdkServiceException} が当たる。
 *
 * <p>資格情報と権限の不備や SES の設定の不備は SES の不調ではないので数えず、スロットリングは一時障害なので数える（ADR-124）。 application.yaml の
 * {@code ignore-exception-predicate} から Resilience4j が引数のないコンストラクタで作るため、public にする。
 */
public final class IgnoredSesClientErrorPredicate implements Predicate<Throwable> {

  /** Resilience4j が反射で作る。 */
  public IgnoredSesClientErrorPredicate() {
    // 状態を持たない。
  }

  /** スロットリング以外の 4xx なら true を返す。 */
  @Override
  public boolean test(final Throwable throwable) {
    return throwable instanceof SdkServiceException exception
        && HttpStatusFamily.of(exception.statusCode()) == HttpStatusFamily.CLIENT_ERROR
        && !exception.isThrottlingException();
  }
}
