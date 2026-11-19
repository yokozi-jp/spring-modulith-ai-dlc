package com.example.demo.shared.security;

import java.io.Serial;
import org.springframework.security.access.AccessDeniedException;

/**
 * テナントを選ぶ前の利用者が、テナントの決まっている必要がある API を呼んだことを表す。
 *
 * <p>{@code error} の {@code ApiExceptionHandler} が、権限不足の 403 と区別できる専用の type の 403 にする。
 */
public final class TenantNotSelectedException extends AccessDeniedException {

  @Serial private static final long serialVersionUID = 1L;

  /** 失敗の説明を受け取る。説明は応答に入れない。 */
  public TenantNotSelectedException(final String message) {
    super(message);
  }
}
