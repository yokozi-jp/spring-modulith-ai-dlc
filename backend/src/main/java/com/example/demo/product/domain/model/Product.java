package com.example.demo.product.domain.model;

import java.math.BigDecimal;

/** 商品の集約ルート。ステップ 1 では参照だけで、状態を変える操作はない。 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
// ステップ 1 の商品は参照だけで状態を変える操作がないため、データだけのクラスを許す。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName", "PMD.DataClass"})
public final class Product {

  /** 商品 ID。 */
  private final ProductId id;

  /** 商品コード。 */
  private final String productCode;

  /** 商品名。 */
  private final String productName;

  /** 単価。 */
  private final BigDecimal unitPrice;

  /** 販売の状態。 */
  private final SalesStatus salesStatus;

  private Product(
      final ProductId id,
      final String productCode,
      final String productName,
      final BigDecimal unitPrice,
      final SalesStatus salesStatus) {
    this.id = id;
    this.productCode = productCode;
    this.productName = productName;
    this.unitPrice = unitPrice;
    this.salesStatus = salesStatus;
  }

  /** 保存済みの商品を復元する。Repository の実装が使う。 */
  public static Product restore(
      final ProductId id,
      final String productCode,
      final String productName,
      final BigDecimal unitPrice,
      final SalesStatus salesStatus) {
    return new Product(id, productCode, productName, unitPrice, salesStatus);
  }

  /** 商品 ID を返す。 */
  public ProductId id() {
    return id;
  }

  /** 商品コードを返す。 */
  public String productCode() {
    return productCode;
  }

  /** 商品名を返す。 */
  public String productName() {
    return productName;
  }

  /** 単価を返す。 */
  public BigDecimal unitPrice() {
    return unitPrice;
  }

  /** 販売の状態を返す。 */
  public SalesStatus salesStatus() {
    return salesStatus;
  }
}
