package archfixture.conforming.order.domain.model;

import archfixture.conforming.shared.concurrency.ConflictException;
import archfixture.conforming.shared.concurrency.ExpectedLockNo;
import archfixture.conforming.shared.failure.BusinessRuleViolationException;
import java.time.Instant;

/** 注文の集約ルート。 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName"})
public final class Order {

  /** 注文 ID。 */
  private final OrderId id;

  /** 注文した顧客。 */
  private final CustomerId customerId;

  /** 受け付けた時刻。 */
  private final Instant placedAt;

  /** 読み込んだときの版。 */
  private final long lockNo;

  /** 注文の状態。 */
  private OrderStatus status;

  private Order(final OrderId id, final CustomerId customerId, final Instant placedAt) {
    this.id = id;
    this.customerId = customerId;
    this.placedAt = placedAt;
    this.lockNo = 1L;
    this.status = OrderStatus.PLACED;
  }

  /** 注文を受け付ける。 */
  public static Order place(final OrderId id, final CustomerId customerId, final Instant placedAt) {
    return new Order(id, customerId, placedAt);
  }

  /** 受付の注文を取り消す。 */
  public void cancel() {
    if (status != OrderStatus.PLACED) {
      throw new BusinessRuleViolationException("order is not placed: orderId=" + id.value());
    }
    status = OrderStatus.CANCELLED;
  }

  /** 画面が表示した版が、読み込んだ版と同じことを確かめる。 */
  public void ensureLockNo(final ExpectedLockNo expectedLockNo) {
    if (lockNo != expectedLockNo.value()) {
      throw new ConflictException("order was updated by another request: orderId=" + id.value());
    }
  }

  /** 読み込んだときの版を返す。 */
  public long lockNo() {
    return lockNo;
  }

  /** 注文 ID を返す。 */
  public OrderId id() {
    return id;
  }

  /** 注文した顧客を返す。 */
  public CustomerId customerId() {
    return customerId;
  }

  /** 受け付けた時刻を返す。 */
  public Instant placedAt() {
    return placedAt;
  }

  /** 注文の状態を返す。 */
  public OrderStatus status() {
    return status;
  }
}
