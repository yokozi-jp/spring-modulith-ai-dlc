package archfixture.violating.shared.failure;

/** 422 になる業務規則の違反の理由。 */
// enum が実装する interface で、ラムダで作らない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface FailureReason {

  /** 理由の定数の名前を返す。 */
  String name();
}
