package com.example.demo.shared.security;

import java.io.Serial;
import org.springframework.security.access.AccessDeniedException;

/**
 * 別のオリジンから来た更新系の要求を、CSRF のトークンを確かめる前に拒否したことを表す（ADR-134）。
 *
 * <p>{@code error} の {@code ApiExceptionHandler} が、CSRF のトークンの失敗と区別できる専用の type の 403 にする。
 */
public final class CrossOriginRequestRejectedException extends AccessDeniedException {

  @Serial private static final long serialVersionUID = 1L;

  /** 失敗の説明を受け取る。説明は応答に入れない。 */
  public CrossOriginRequestRejectedException(final String message) {
    super(message);
  }
}
