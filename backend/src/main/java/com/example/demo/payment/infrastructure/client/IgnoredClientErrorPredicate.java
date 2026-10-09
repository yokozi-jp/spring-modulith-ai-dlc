package com.example.demo.payment.infrastructure.client;

import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * 決済代行の circuit breaker が失敗に数えない例外を選ぶ。429 以外の {@link HttpClientErrorException} が当たる。
 *
 * <p>429 は ADR-019 の一時障害なので失敗に数える。{@code ignore-exceptions} の型の列挙では 429 だけを残せないため、述語にする（ADR-072）。
 * application.yaml の {@code ignore-exception-predicate} から Resilience4j が引数のないコンストラクタで作るため、public
 * にする。
 */
public final class IgnoredClientErrorPredicate implements Predicate<Throwable> {

  /** Resilience4j が反射で作る。 */
  public IgnoredClientErrorPredicate() {
    // 状態を持たない。
  }

  /** 429 以外の 4xx なら true を返す。 */
  @Override
  public boolean test(final Throwable throwable) {
    return throwable instanceof HttpClientErrorException exception
        && exception.getStatusCode().value() != HttpStatus.TOO_MANY_REQUESTS.value();
  }
}
