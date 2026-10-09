package com.example.demo.product.infrastructure.persistence;

import static com.example.demo.jooq.product.Tables.M_PRODUCT;

import com.example.demo.product.domain.model.Product;
import com.example.demo.product.domain.model.ProductId;
import com.example.demo.product.domain.model.ProductRepository;
import com.example.demo.product.domain.model.SalesStatus;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record5;
import org.jooq.Records;
import org.jooq.SelectJoinStep;
import org.springframework.stereotype.Repository;

/** 商品の集約を jOOQ で取り出す。書き込みがないため、共通カラムと TableWriter を使わない。 */
@Repository
class JooqProductRepository implements ProductRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** jOOQ のコンテキストを受け取る。 */
  /* package */ JooqProductRepository(final DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<Product> findAll() {
    return selectProducts()
        .orderBy(M_PRODUCT.PRODUCT_CODE)
        .fetch(Records.mapping(Product::restore));
  }

  @Override
  public List<Product> findByIds(final Collection<ProductId> ids) {
    return selectProducts()
        .where(M_PRODUCT.PUBLIC_ID.in(ids.stream().map(ProductId::value).toList()))
        .orderBy(M_PRODUCT.PRODUCT_CODE)
        .fetch(Records.mapping(Product::restore));
  }

  /** 商品の列を、Product.restore の引数の型と順に選ぶ。 */
  private SelectJoinStep<Record5<ProductId, String, String, BigDecimal, SalesStatus>>
      selectProducts() {
    return dsl.select(
            M_PRODUCT.PUBLIC_ID.convertFrom(ProductId::new),
            M_PRODUCT.PRODUCT_CODE,
            M_PRODUCT.PRODUCT_NAME,
            M_PRODUCT.UNIT_PRICE_JPY,
            M_PRODUCT.SALES_STATUS_TYP.convertFrom(SalesStatus::valueOf))
        .from(M_PRODUCT);
  }
}
