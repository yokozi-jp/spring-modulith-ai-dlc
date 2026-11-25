package com.example.demo.product.domain.model;

import com.example.demo.shared.concurrency.Versioned;
import com.example.demo.shared.failure.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.util.UUID;

/** 商品の集約ルート。登録した後に状態を変える操作はない。 */
// record と同じ形のアクセサ（id() など）にそろえるため、フィールド名と同名の短いメソッドを許す。
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.ShortMethodName", "PMD.DataClass"})
public final class Product implements Versioned {

  /** 単価の下限（円）。商品を登録する API の本文の検証と同じ値にする。 */
  private static final BigDecimal MIN_UNIT_PRICE = new BigDecimal("0.01");

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

  /** 読み込んだときのロック番号。 */
  private final long lockNo;

  private Product(
      final ProductId id,
      final String productCode,
      final String productName,
      final BigDecimal unitPrice,
      final SalesStatus salesStatus,
      final long lockNo) {
    this.id = id;
    this.productCode = productCode;
    this.productName = productName;
    this.unitPrice = unitPrice;
    this.salesStatus = salesStatus;
    this.lockNo = lockNo;
  }

  /**
   * 商品を登録する。商品 ID は UUID v4 で採番する（ADR-060）。
   *
   * @param productCode テナントの中で一意の商品コード
   * @param productName 商品名
   * @param unitPrice 単価
   * @param salesStatus 販売の状態
   * @throws BusinessRuleViolationException 商品コードか商品名が空の場合、単価が 0.01 円より小さい場合
   */
  public static Product register(
      final String productCode,
      final String productName,
      final BigDecimal unitPrice,
      final SalesStatus salesStatus) {
    if (productCode.isBlank()) {
      throw new BusinessRuleViolationException(
          ProductFailure.BLANK_PRODUCT_CODE, "product code must not be blank");
    }
    if (productName.isBlank()) {
      throw new BusinessRuleViolationException(
          ProductFailure.BLANK_PRODUCT_NAME,
          "product name must not be blank: productCode=" + productCode);
    }
    if (unitPrice.compareTo(MIN_UNIT_PRICE) < 0) {
      throw new BusinessRuleViolationException(
          ProductFailure.UNIT_PRICE_BELOW_MINIMUM,
          "unit price is below minimum: productCode=" + productCode + ", unitPrice=" + unitPrice);
    }
    return new Product(
        new ProductId(UUID.randomUUID()),
        productCode,
        productName,
        unitPrice,
        salesStatus,
        FIRST_LOCK_NO);
  }

  /** 保存済みの商品を復元する。Repository の実装が使う。 */
  public static Product restore(
      final ProductId id,
      final String productCode,
      final String productName,
      final BigDecimal unitPrice,
      final SalesStatus salesStatus,
      final long lockNo) {
    return new Product(id, productCode, productName, unitPrice, salesStatus, lockNo);
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

  @Override
  public long lockNo() {
    return lockNo;
  }
}
