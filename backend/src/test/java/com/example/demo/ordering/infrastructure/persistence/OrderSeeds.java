package com.example.demo.ordering.infrastructure.persistence;

import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.OrderId;
import com.example.demo.ordering.domain.model.OrderLine;
import com.example.demo.ordering.domain.model.OrderStatus;
import com.example.demo.ordering.domain.model.ProductOffer;
import com.example.demo.ordering.domain.model.Quantity;
import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.TableWriter;
import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;

/**
 * 開発用の代表データ（#114）の注文 C01〜C06 を入れる。DRAFT 2 件、CONFIRMED 3 件、CANCELLED 1 件にする。
 *
 * <p>CONFIRMED は、決済済み（C03）、決済代行が拒否した決済（C05）、決済記録の無い再投入待ち（C06）の 3 つの状態に使う（#167）。
 */
public final class OrderSeeds {

  /** シーダーが書く {@code *_pgm_cd}。 */
  private static final String PGM_CD = "ordering.SeedLocalData";

  /** 受け取る商品の数（P01〜P04）。 */
  private static final int OFFER_COUNT = 4;

  private OrderSeeds() {}

  /**
   * 注文を入れ、決済記録を付ける CONFIRMED の C03 と C05 を返す。
   *
   * @param offers P01〜P04 の順の販売中の商品
   * @param ids 注文 ID を C01〜C06 の順に 1 つずつ引く
   * @param base {@code ordered_at} の基準。C01〜C06 は 6〜1 時間前にする
   */
  public static Confirmed insert(
      final DSLContext dsl,
      final List<ProductOffer> offers,
      final Supplier<UUID> ids,
      final Instant base) {
    if (offers.size() != OFFER_COUNT || !offers.stream().allMatch(ProductOffer::onSale)) {
      throw new IllegalArgumentException(
          "offers must be 4 products on sale: size=" + offers.size());
    }
    final ProductOffer p01 = offers.get(0);
    final ProductOffer p02 = offers.get(1);
    final ProductOffer p03 = offers.get(2);
    final ProductOffer p04 = offers.get(3);
    final List<Order> orders = new ArrayList<>();
    TestCommonColumns.runAs(
        PGM_CD,
        () -> {
          orders.add(add(dsl, ids, base, 1, OrderStatus.DRAFT, line(p01, 1), line(p02, 2)));
          orders.add(add(dsl, ids, base, 2, OrderStatus.DRAFT, line(p03, 1)));
          orders.add(add(dsl, ids, base, 3, OrderStatus.CONFIRMED, line(p01, 2), line(p04, 1)));
          orders.add(add(dsl, ids, base, 4, OrderStatus.CANCELLED, line(p02, 1)));
          // C05 と C06 は C04 の後に引き、C01〜C04 の ID を変えない。
          orders.add(add(dsl, ids, base, 5, OrderStatus.CONFIRMED, line(p03, 2)));
          orders.add(add(dsl, ids, base, 6, OrderStatus.CONFIRMED, line(p04, 1), line(p03, 1)));
        });
    return new Confirmed(orders.get(2), orders.get(4));
  }

  private static Order add(
      final DSLContext dsl,
      final Supplier<UUID> ids,
      final Instant base,
      final int number,
      final OrderStatus status,
      final Line... lines) {
    final List<OrderLine> orderLines = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      final Line line = lines[i];
      orderLines.add(
          new OrderLine(
              i + 1,
              line.offer().productId(),
              new Quantity(line.quantity()),
              line.offer().unitPrice()));
    }
    final Instant orderedAt = base.minus(Duration.ofHours(7L - number));
    final Order order =
        Order.restore(
            new OrderId(ids.get()), "SEED-C0" + number, status, orderLines, orderedAt, 1L);
    // JooqOrderRepository は CommonColumns の Clock から時刻を取るので、注文ごとに作る。
    final CommonColumns commonColumns = TestCommonColumns.at(orderedAt);
    new JooqOrderRepository(dsl, commonColumns, new TableWriter(dsl, commonColumns)).add(order);
    return order;
  }

  private static Line line(final ProductOffer offer, final int quantity) {
    return new Line(offer, quantity);
  }

  /**
   * 決済記録を付ける CONFIRMED の注文。
   *
   * @param paid 決済済みにする C03
   * @param declined 決済代行が拒否した決済にする C05
   */
  public record Confirmed(Order paid, Order declined) {}

  /** 明細の商品と数量。 */
  private record Line(ProductOffer offer, int quantity) {}
}
