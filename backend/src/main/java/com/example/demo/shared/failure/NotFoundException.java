package com.example.demo.shared.failure;

import java.io.Serial;

/**
 * 指定された集約や行が存在しないことを表す。参照の権限がなく存在を隠す場合にも投げる。
 *
 * <p>HTTP では 404 Not Found になる。
 */
public final class NotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから見つからないことの例外を作る。 */
  public NotFoundException(final String message) {
    super(message);
  }
}
