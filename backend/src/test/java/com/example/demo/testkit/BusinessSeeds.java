package com.example.demo.testkit;

import com.example.demo.ordering.domain.model.Money;
import com.example.demo.ordering.domain.model.Order;
import com.example.demo.ordering.domain.model.ProductId;
import com.example.demo.ordering.domain.model.ProductOffer;
import com.example.demo.ordering.infrastructure.persistence.OrderSeeds;
import com.example.demo.payment.infrastructure.persistence.PaymentSeeds;
import com.example.demo.product.ProductSeeds;
import com.example.demo.shared.tenant.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.datafaker.Faker;
import org.jooq.DSLContext;

/**
 * 1 つのテナントの業務テーブルに、開発用の代表データ（#114）の商品、注文、明細、決済を、そのテナントを設定したモジュールのロールで入れる（ADR-107）。
 *
 * <p>開発用のシーダーと、別のテナントの行を読み書きするテストが同じ行を使う。
 */
public final class BusinessSeeds {

  private BusinessSeeds() {}

  /**
   * テナントに代表データを入れる。
   *
   * @param dsl jOOQ のコンテキスト
   * @param tenant 行を入れるテナント
   * @param faker 商品の名前と単価を作る
   * @param ids {@code public_id} を作る
   * @param base 注文の時刻。商品はその 1 日前、決済は注文の 1 分後に入れる
   */
  public static void insert(
      final DSLContext dsl,
      final TenantId tenant,
      final Faker faker,
      final Supplier<UUID> ids,
      final Instant base) {
    // faker と ids を引く順（商品、注文、決済）を変えると、開発用の代表データの値が変わる。
    final List<ProductOffer> offers =
        AsModule.call(
                dsl,
                "product",
                tenant,
                tx -> ProductSeeds.insert(tx, faker, ids, base.minus(Duration.ofDays(1))))
            .stream()
            .filter(ProductSeeds.Seeded::onSale)
            .map(s ->
                new ProductOffer(new ProductId(s.publicId()), new Money(s.unitPrice()), s.onSale()))
            .toList();
    final OrderSeeds.Confirmed confirmed =
        AsModule.call(dsl, "ordering", tenant, tx -> OrderSeeds.insert(tx, offers, ids, base));
    AsModule.run(
        dsl,
        "payment",
        tenant,
        tx -> {
          insertPayment(tx, ids, confirmed.paid(), false);
          insertPayment(tx, ids, confirmed.declined(), true);
        });
  }

  /** 注文の合計金額で、確定の 1 分後に決済記録を入れる。 */
  private static void insertPayment(
      final DSLContext dsl, final Supplier<UUID> ids, final Order order, final boolean declined) {
    PaymentSeeds.insert(
        dsl,
        ids.get(),
        order.id().value(),
        order.total().amount(),
        declined,
        order.orderedAt().plus(Duration.ofMinutes(1)));
  }
}
