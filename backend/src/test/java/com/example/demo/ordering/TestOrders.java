package com.example.demo.ordering;

import com.example.demo.ordering.application.DraftOrderCommand;
import com.example.demo.ordering.application.DraftOrderCommandHandler;
import com.example.demo.product.TestProducts;
import com.example.demo.testkit.UniqueCodes;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/** 確定や参照のテストが使う下書きの注文を、公開の CommandHandler で作る補助。 */
// テストの補助であり、テストケースを持たない。
@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class TestOrders {

  private TestOrders() {}

  /**
   * 単価 120 円の販売中の商品を 2 個注文する下書きを作り、注文 ID を返す。
   *
   * <p>共通カラムの trace ID を取れるよう、observation の中で作る。
   */
  public static String drafted(
      final DSLContext dsl,
      final DraftOrderCommandHandler draftOrder,
      final ObservationRegistry observationRegistry) {
    final UUID productId = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
    return Observation.createNotStarted("order-test", observationRegistry)
        .observe(
            () ->
                draftOrder
                    .handle(
                        new DraftOrderCommand(
                            UniqueCodes.next("C"),
                            List.of(new DraftOrderCommand.Line(productId.toString(), 2))))
                    .orderId());
  }
}
