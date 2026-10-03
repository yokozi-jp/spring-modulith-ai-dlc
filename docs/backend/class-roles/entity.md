---
type: Convention
title: クラスの役割：Entity
description: 集約の中で識別子を持つ Entity の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。集約の中に明細のような識別子を持つ型を作るとき、Entity の状態を変える操作を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Entity

Entity は、集約の中で識別子によって区別する型であり、`domain.model` に `public final class` として置く。
等価性は識別子だけで判定し、状態は集約ルートのメソッドを通してだけ変える。
状態を変えるメソッドはパッケージプライベートにする。
役割の決定理由は [ADR-048](../../adr/ADR-048-define-backend-class-roles-and-naming.md) に示す。

## 定義

[集約](aggregate.md)の中には、値が変わっても同じものとして扱う要素がある。
**Entity** は、そのような要素を識別子で区別する型である。
注文の明細（`OrderLine`）は、数量が変わっても明細番号（`lineNumber`）が同じなら同じ明細である。

集約ルート（`Order`）も Entity の一種だが、扱いは[集約](aggregate.md)に示す。
この文書は、集約ルート以外の Entity を扱う。

Entity は[値オブジェクト](value-object.md)ではない。
値オブジェクトはすべての値で等価性を判定し、状態を変えない。

## 置き場所と命名

- 集約ルートと同じ `com.example.demo.<feature>.domain.model` に置く。
- 名前はユビキタス言語の名詞にする（`OrderLine`）。
  `OrderLineEntity`、`OrderLineData` にしない。
- 識別子は集約の中で一意な値にする（`lineNumber`）。
- 状態を変えるメソッドは業務の動詞にし（`changeQuantity`）、アクセサは `get` を付けず record と同じ形にする。

## 必須の記述

- `public final class` にし、アノテーションを付けない。
- コンストラクタは public にし、集約ルートが明細を作るときに呼ぶ。
- 識別子と変わらない値は `private final` フィールドにし、変わる状態（`quantity`）だけを final でないフィールドにする。
- 状態を変えるメソッドは、`/* package */` を付けたパッケージプライベートにする。
  集約ルートだけが、同じパッケージからこのメソッドを呼ぶ。
- `equals` と `hashCode` を識別子だけで実装する。
- アクセサがフィールド名と同じ名前になるため、クラスに `@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")` を理由のコメントと一緒に付ける。
- クラス、フィールド、コンストラクタ、public メソッドに Javadoc を書く。
- パッケージの `package-info.java` は集約と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じ `domain.model` の値オブジェクトと enum。
- **依存してはいけない型**：Spring、jOOQ、jOOQ の生成型、JPA、Jackson の型、`domain.service`、`application`、モジュールルートの型、集約ルート、Repository と外部システムのインタフェース。

## 最小の例と典型的な例

最小の例は、注文の明細の `OrderLine` である。

```java
package com.example.demo.order.domain.model;

/** 注文の明細。注文の中で明細番号によって識別する。 */
// record と同じ形のアクセサ（lineNumber() など）にそろえるため、フィールド名と同名のメソッドを許す。
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class OrderLine {

  /** 注文の中の明細番号。 */
  private final int lineNumber;

  /** 商品コード。 */
  private final ProductCode productCode;

  /** 単価。 */
  private final Money unitPrice;

  /** 数量。 */
  private Quantity quantity;

  /** 明細を作る。 */
  public OrderLine(
      final int lineNumber,
      final ProductCode productCode,
      final Quantity quantity,
      final Money unitPrice) {
    this.lineNumber = lineNumber;
    this.productCode = productCode;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
  }

  /** 単価に数量を掛けた小計を返す。 */
  public Money subtotal() {
    return unitPrice.times(quantity);
  }

  /** 数量を変える。注文の changeLineQuantity だけが呼ぶ。 */
  /* package */ void changeQuantity(final Quantity quantity) {
    this.quantity = quantity;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof OrderLine line && lineNumber == line.lineNumber;
  }

  @Override
  public int hashCode() {
    return Integer.hashCode(lineNumber);
  }

  // lineNumber()、productCode()、quantity()、unitPrice() のアクセサも同じ形で書く。
}
```

典型的な例は、集約ルートの `Order` が明細の数量を変える場面である。
外からは `Order.changeLineQuantity` だけを呼び、`OrderLine.changeQuantity` を直接呼ばない。

```java
// com.example.demo.order.domain.model.Order（抜粋）
/** 受付の注文の明細の数量を変える。 */
public void changeLineQuantity(final int lineNumber, final Quantity quantity) {
  ensureStatus(OrderStatus.PLACED);
  lines.stream()
      .filter(line -> line.lineNumber() == lineNumber)
      .findFirst()
      .orElseThrow(
          () ->
              new IllegalArgumentException(
                  "order line not found: orderId=" + id.value() + ", lineNumber=" + lineNumber))
      .changeQuantity(quantity);
}
```

## 対応するテスト

Spring を起動しない JUnit のテストを、Entity と同じパッケージのテストソースに置く（`OrderLineTest`）。
状態の変更は、集約ルートの操作として[集約](aggregate.md)のテストで確かめる。

```java
/** 注文の明細の計算と等価性を検証する。 */
class OrderLineTest {

  @Test
  @DisplayName("明細番号が同じ明細は、数量が違っても同じ明細とみなす")
  void linesWithSameNumberAreEqual() {
    final Money unitPrice = new Money(new BigDecimal("500"));
    final OrderLine line = new OrderLine(1, new ProductCode("P-1"), new Quantity(1), unitPrice);
    final OrderLine other = new OrderLine(1, new ProductCode("P-1"), new Quantity(3), unitPrice);

    assertThat(line).isEqualTo(other);
  }
}
```

## アンチパターン

- 状態を変えるメソッドを public にし、CommandHandler が `OrderLine` を直接変える。
  集約ルートの不変条件（受付のときだけ数量を変えられる）を通らずに状態が変わる。
- `equals` と `hashCode` をすべてのフィールドで実装する。
  数量を変えた明細が別の明細になる。
- Entity を record にする。
  record はすべての component で等価性を判定し、状態を変えられない。
- Entity が親の集約ルートをフィールドで持つ。
- Entity ごとに Repository を作る（`OrderLineRepository`）。

## 作成時のチェックリスト

- [ ] `domain.model` に置き、Spring、jOOQ、JPA、Jackson に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks］
- [ ] `domain.service`、`application`、モジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `public final class` にし、集約の中で一意な識別子を `private final` フィールドに持つ。［自分で点検］
- [ ] `equals` と `hashCode` を識別子だけで実装する。［自分で点検］
- [ ] 状態を変えるメソッドを `/* package */` のパッケージプライベートにし、集約ルートのメソッドから呼ぶ。［自分で点検］
- [ ] Entity 用の Repository を作らない。［自分で点検］
- [ ] アクセサの `@SuppressWarnings` に理由のコメントを付け、クラス、フィールド、コンストラクタ、public メソッドに Javadoc を書く。［自分で点検］
- [ ] 識別子による等価性を、Spring を起動しない JUnit のテストで確かめる。［自分で点検］
- [ ] `domain.model` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
