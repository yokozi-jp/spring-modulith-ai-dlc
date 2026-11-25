package archfixture.conforming.shared.idempotency;

/** キーを持つ Command の印。 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface IdempotentCommand {

  /** クライアントが採番したキーを返す。 */
  IdempotencyKey idempotencyKey();
}
