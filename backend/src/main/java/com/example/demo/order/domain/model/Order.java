package com.example.demo.order.domain.model;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 注文の集約ルート。下書き（DRAFT）で作り、確定（CONFIRMED）か取消（CANCELLED）にする。 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
// 集約ルートは状態遷移の操作とアクセサを一つのクラスに持つため、メソッドの数の上限を外す。
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods"
})
public final class Order {

  /** 注文 ID。 */
  private final OrderId id;

  /** 客先注文番号。 */
  private final String customerOrderCode;

  /** 作成した時刻。 */
  private final Instant orderedAt;

  /** 楽観的ロックのロック番号。 */
  private final long lockNo;

  /** 注文の状態。 */
  private OrderStatus status;

  /** 注文の明細。番号の昇順に並ぶ。 */
  private List<OrderLine> lines;

  /** 明細が空でないことを確かめて状態を持つ。 */
  private Order(
      final OrderId id,
      final String customerOrderCode,
      final OrderStatus status,
      final List<OrderLine> lines,
      final Instant orderedAt,
      final long lockNo) {
    this.id = id;
    this.customerOrderCode = customerOrderCode;
    this.status = status;
    this.lines = requireLines(id, lines);
    this.orderedAt = orderedAt;
    this.lockNo = lockNo;
  }

  /**
   * 下書きの注文を作る。注文 ID は UUID v4 で採番する（ADR-060）。
   *
   * @param customerOrderCode 客先注文番号
   * @param items 画面が指定した明細
   * @param offers 明細の商品の単価と販売の状態。存在しない商品は含まない
   * @param orderedAt 作成した時刻
   * @throws BusinessRuleViolationException 存在しないか販売終了の商品を指定した場合、明細が空の場合
   */
  public static Order draft(
      final String customerOrderCode,
      final List<OrderedItem> items,
      final Map<ProductId, ProductOffer> offers,
      final Instant orderedAt) {
    final OrderId id = new OrderId(UUID.randomUUID());
    return new Order(
        id, customerOrderCode, OrderStatus.DRAFT, priceLines(id, items, offers), orderedAt, 1L);
  }

  /** 保存済みの注文を復元する。Repository の実装が使う。 */
  public static Order restore(
      final OrderId id,
      final String customerOrderCode,
      final OrderStatus status,
      final List<OrderLine> lines,
      final Instant orderedAt,
      final long lockNo) {
    return new Order(id, customerOrderCode, status, lines, orderedAt, lockNo);
  }

  /**
   * 下書きの注文の明細を置き換える。明細の番号は 1 から振り直し、単価は渡された商品から読み直す。
   *
   * @throws BusinessRuleViolationException 下書きでない場合、存在しないか販売終了の商品を指定した場合、明細が空の場合
   */
  public void changeLines(
      final List<OrderedItem> items, final Map<ProductId, ProductOffer> offers) {
    ensureDraft();
    lines = requireLines(id, priceLines(id, items, offers));
  }

  /**
   * 下書きの注文を確定する。
   *
   * @throws BusinessRuleViolationException 下書きでない場合
   */
  public void confirm() {
    ensureDraft();
    status = OrderStatus.CONFIRMED;
  }

  /**
   * 下書きの注文を取り消す。
   *
   * @throws BusinessRuleViolationException 下書きでない場合
   */
  public void cancel() {
    ensureDraft();
    status = OrderStatus.CANCELLED;
  }

  /**
   * 画面が読んだ注文のロック番号が、保存済みの注文のロック番号と一致することを確かめる。
   *
   * @throws ConflictException 一致しない場合
   */
  public void ensureLockNo(final ExpectedLockNo expectedLockNo) {
    if (lockNo != expectedLockNo.value()) {
      throw new ConflictException(
          String.format(
              Locale.ROOT,
              "lock number mismatch: orderId=%s, expectedLockNo=%d, lockNo=%d",
              id.value(),
              expectedLockNo.value(),
              lockNo));
    }
  }

  /** 明細の金額の合計を返す。 */
  public Money total() {
    return lines.stream().map(OrderLine::amount).reduce(Money.ZERO, Money::plus);
  }

  /** 注文 ID を返す。 */
  public OrderId id() {
    return id;
  }

  /** 客先注文番号を返す。 */
  public String customerOrderCode() {
    return customerOrderCode;
  }

  /** 注文の状態を返す。 */
  public OrderStatus status() {
    return status;
  }

  /** 注文の明細を、番号の昇順の変更できないリストで返す。 */
  public List<OrderLine> lines() {
    return List.copyOf(lines);
  }

  /** 作成した時刻を返す。 */
  public Instant orderedAt() {
    return orderedAt;
  }

  /** 楽観的ロックのロック番号を返す。 */
  public long lockNo() {
    return lockNo;
  }

  private void ensureDraft() {
    if (status != OrderStatus.DRAFT) {
      throw new BusinessRuleViolationException(
          "order is not draft: orderId=" + id.value() + ", status=" + status);
    }
  }

  /** 画面が指定した明細を、商品の単価で 1 から番号を振った明細にする。 */
  private static List<OrderLine> priceLines(
      final OrderId id, final List<OrderedItem> items, final Map<ProductId, ProductOffer> offers) {
    final List<OrderLine> priced = new ArrayList<>(items.size());
    for (final OrderedItem item : items) {
      final @Nullable ProductOffer offer = offers.get(item.productId());
      if (offer == null) {
        throw new BusinessRuleViolationException(
            "product not found: orderId=" + id.value() + ", productId=" + item.productId().value());
      }
      if (!offer.onSale()) {
        throw new BusinessRuleViolationException(
            "product is discontinued: orderId="
                + id.value()
                + ", productId="
                + item.productId().value());
      }
      priced.add(
          new OrderLine(priced.size() + 1, item.productId(), item.quantity(), offer.unitPrice()));
    }
    return priced;
  }

  private static List<OrderLine> requireLines(final OrderId id, final List<OrderLine> lines) {
    if (lines.isEmpty()) {
      throw new BusinessRuleViolationException(
          "order lines must not be empty: orderId=" + id.value());
    }
    return List.copyOf(lines);
  }
}
