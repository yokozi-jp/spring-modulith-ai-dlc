package com.example.demo.product.infrastructure.persistence;

import static com.example.demo.jooq.product.Tables.M_PRODUCT;

import com.example.demo.jooq.product.tables.records.MProductRecord;
import com.example.demo.product.domain.model.Product;
import com.example.demo.product.domain.model.ProductId;
import com.example.demo.product.domain.model.ProductRepository;
import com.example.demo.product.domain.model.SalesStatus;
import com.example.demo.shared.persistence.AggregateTable;
import com.example.demo.shared.persistence.TableWriter;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record6;
import org.jooq.Records;
import org.jooq.SelectJoinStep;
import org.springframework.stereotype.Repository;

/** 商品の集約を jOOQ で保存し、取り出す。 */
@Repository
class JooqProductRepository implements ProductRepository {

  /** 商品の集約。子はなく、ルートは公開 ID で一つに決める。 */
  private static final AggregateTable<MProductRecord, UUID, Product> PRODUCT =
      AggregateTable.of(
          M_PRODUCT, M_PRODUCT.PUBLIC_ID, product -> product.id().value(), M_PRODUCT.PRODUCT_ID);

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 商品の集約を INSERT で書く shared の入口。 */
  private final TableWriter tableWriter;

  /* package */ JooqProductRepository(final DSLContext dsl, final TableWriter tableWriter) {
    this.dsl = dsl;
    this.tableWriter = tableWriter;
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

  @Override
  public void add(final Product product) {
    tableWriter.insert(
        PRODUCT,
        product,
        set ->
            set.set(M_PRODUCT.PRODUCT_CODE, product.productCode())
                .set(M_PRODUCT.PRODUCT_NAME, product.productName())
                .set(M_PRODUCT.UNIT_PRICE_JPY, product.unitPrice())
                .set(M_PRODUCT.SALES_STATUS_TYP, product.salesStatus().name()));
  }

  /** 商品の列を、Product.restore の引数の型と順に選ぶ。 */
  private SelectJoinStep<Record6<ProductId, String, String, BigDecimal, SalesStatus, Long>>
      selectProducts() {
    return dsl.select(
            M_PRODUCT.PUBLIC_ID.convertFrom(ProductId::new),
            M_PRODUCT.PRODUCT_CODE,
            M_PRODUCT.PRODUCT_NAME,
            M_PRODUCT.UNIT_PRICE_JPY,
            M_PRODUCT.SALES_STATUS_TYP.convertFrom(SalesStatus::valueOf),
            M_PRODUCT.LOCK_NO)
        .from(M_PRODUCT);
  }
}
