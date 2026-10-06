package archfixture.violating.order.domain.model;

import archfixture.violating.shared.concurrency.ExpectedLockNo;

/** 違反フィクスチャが保存する集約ルート。これ自体は規約どおり。 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class Order {

  /** 注文 ID。 */
  private final OrderId id;

  /** 読み込んだときの版。 */
  private final long lockNo;

  /** 注文 ID と版を受け取る。 */
  public Order(final OrderId id, final long lockNo) {
    this.id = id;
    this.lockNo = lockNo;
  }

  /** 画面が表示した版と比べる。違反フィクスチャが handle の外から呼ぶ。 */
  public void ensureLockNo(final ExpectedLockNo expected) {
    ensureLockNo(expected.value());
  }

  /** 違反：commandHandlersEnsureScreenLockNo の対象外にするための、数値を受け取るオーバーロード。 */
  public void ensureLockNo(final long expected) {
    if (lockNo != expected) {
      throw new IllegalStateException("order was updated by another request");
    }
  }

  /** 注文 ID を返す。 */
  public OrderId id() {
    return id;
  }

  /** 読み込んだときの版を返す。 */
  public long lockNo() {
    return lockNo;
  }
}
