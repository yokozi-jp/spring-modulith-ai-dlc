package archfixture.violating.shared.idempotency;

import java.util.function.Supplier;

/** 同じキーの再送に保存した Result を返す入口。 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface Idempotency {

  /** キーが新しければ業務の処理を実行し、そうでなければ保存した Result を返す。 */
  <R> R execute(IdempotentCommand command, Class<R> resultType, Supplier<R> operation);
}
