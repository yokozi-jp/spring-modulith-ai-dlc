package archfixture.violating.shared.concurrency;

import java.io.Serial;

/** 楽観的ロックの競合を表す。 */
public final class ConflictException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** メッセージから競合の例外を作る。 */
  public ConflictException(final String message) {
    super(message);
  }
}
