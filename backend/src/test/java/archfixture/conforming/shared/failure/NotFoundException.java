package archfixture.conforming.shared.failure;

import java.io.Serial;

/** 指定された集約や行が存在しないことを表す。 */
public final class NotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** 例外を作る。 */
  public NotFoundException(final String message) {
    super(message);
  }
}
