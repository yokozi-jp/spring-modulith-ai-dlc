package archfixture.conforming.shared.idempotency;

/** 再送を見分けるキー。 */
public record IdempotencyKey(String value) {}
