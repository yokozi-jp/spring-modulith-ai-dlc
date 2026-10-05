package archfixture.violating.shared.concurrency;

/** 画面が表示した版を持つ Command の印。 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface VersionedCommand {

  /** 画面が表示した集約ルートの版を返す。 */
  ExpectedLockNo expectedLockNo();
}
