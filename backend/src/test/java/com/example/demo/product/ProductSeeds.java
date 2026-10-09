package com.example.demo.product;

import com.example.demo.shared.infrastructure.persistence.TestCommonColumns;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.datafaker.Faker;
import org.jooq.DSLContext;

/** 開発用の代表データ（#114）の商品 P01〜P05 を入れる。P05 だけを販売終了にする。 */
public final class ProductSeeds {

  /** シーダーが書く {@code *_pgm_cd}。 */
  private static final String PGM_CD = "product.SeedLocalData";

  /** 入れる商品の数。 */
  private static final int COUNT = 5;

  private ProductSeeds() {}

  /**
   * 入れた商品。
   *
   * @param productCode 商品コード
   * @param publicId 商品 ID
   * @param unitPrice 単価
   * @param onSale 販売中か
   */
  public record Seeded(String productCode, UUID publicId, BigDecimal unitPrice, boolean onSale) {}

  /** 商品を入れ、P01〜P05 の順に返す。乱数は各商品で名前、単価、ID の順に引く。 */
  public static List<Seeded> insert(
      final DSLContext dsl,
      final Faker faker,
      final Supplier<UUID> ids,
      final Instant registeredAt) {
    final List<Seeded> seeded = new ArrayList<>();
    TestCommonColumns.runAs(
        PGM_CD,
        () -> {
          for (int n = 1; n <= COUNT; n++) {
            final String productName = faker.commerce().productName();
            final BigDecimal unitPrice =
                BigDecimal.valueOf(faker.number().numberBetween(10, 500) * 10L).setScale(2);
            final UUID publicId = ids.get();
            final boolean onSale = n < COUNT;
            final String productCode = "SEED-P0" + n;
            TestProducts.insert(
                dsl,
                publicId,
                productCode,
                productName,
                unitPrice,
                onSale ? "ON_SALE" : "DISCONTINUED",
                registeredAt);
            seeded.add(new Seeded(productCode, publicId, unitPrice, onSale));
          }
        });
    return List.copyOf(seeded);
  }
}
