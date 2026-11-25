package com.example.demo.product.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.product.domain.model.Product;
import com.example.demo.product.domain.model.ProductRepository;
import com.example.demo.product.domain.model.SalesStatus;
import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.persistence.TableWriter;
import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.UniqueCodes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 商品の保存と読み戻しを検証する。共通カラムと競合の変換は TableWriterTest が確かめる。 */
@DatabaseTest
class JooqProductRepositoryTest {

  /** 保存の時刻。 */
  private static final Instant ADDED_AT = Instant.parse("2026-10-05T01:00:00.123456Z");

  /** テスト対象が使う jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName("保存した商品を、商品コード、商品名、単価、販売の状態、ロック番号ごと読み戻せる")
  void addsAndFindsProduct() {
    final String productCode = UniqueCodes.next("P");
    final Product product =
        Product.register(productCode, "ボールペン", new BigDecimal("120.50"), SalesStatus.DISCONTINUED);
    final ProductRepository repository = repository();

    TestCommonColumns.runAs(() -> repository.add(product));

    assertThat(repository.findByIds(List.of(product.id())))
        .singleElement()
        .satisfies(found -> {
          assertThat(found.id()).isEqualTo(product.id());
          assertThat(found.productCode()).isEqualTo(productCode);
          assertThat(found.productName()).isEqualTo("ボールペン");
          assertThat(found.unitPrice()).isEqualByComparingTo("120.50");
          assertThat(found.salesStatus()).isEqualTo(SalesStatus.DISCONTINUED);
          assertThat(found.lockNo()).isEqualTo(1L);
        });
  }

  @Test
  @DisplayName("同じ商品コードの商品の保存は、一意制約の ConflictException になる")
  void duplicateProductCodeIsConflict() {
    final String productCode = UniqueCodes.next("P");
    final ProductRepository repository = repository();
    TestCommonColumns.runAs(() -> repository.add(product(productCode)));

    assertThatThrownBy(() -> TestCommonColumns.runAs(() -> repository.add(product(productCode))))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> assertThat(e.kind()).isEqualTo(ConflictException.Kind.UNIQUE));
  }

  private ProductRepository repository() {
    return new JooqProductRepository(dsl, new TableWriter(dsl, TestCommonColumns.at(ADDED_AT)));
  }

  private static Product product(final String productCode) {
    return Product.register(productCode, "消しゴム", new BigDecimal("80.00"), SalesStatus.ON_SALE);
  }
}
