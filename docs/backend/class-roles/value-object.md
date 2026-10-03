---
type: Convention
title: クラスの役割：値オブジェクト
description: domain.model に置く値オブジェクトの定義、置き場所と命名、必須の記述、依存、例、プロパティベーステスト、アンチパターン、作成時のチェックリストを定める。識別子、金額、数量のような値と不変条件の型を作るとき、固定の値の集合を enum にするときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：値オブジェクト

値オブジェクトは、識別子を持たず、値と不変条件を表す型であり、`domain.model` に `public record` として置く。
不変条件はコンパクトコンストラクタで検査し、満たさない値には `IllegalArgumentException` を投げる。
固定の値の集合は enum にする。
役割の決定理由は [ADR-044](../../adr/ADR-044-define-backend-class-roles-and-naming.md) に示す。

## 定義

業務の値を `String` や `BigDecimal` のまま扱うと、取り違えと不正な値を型で防げない。
**値オブジェクト**は、業務の値に名前と不変条件を与える型である。
等価性はすべての値で判定し、作った後で値を変えない。

値オブジェクトは [Entity](entity.md) ではない。
値が変わったら、新しい値オブジェクトを作る（`Money.plus` は新しい `Money` を返す）。

値オブジェクトは Domain の中だけで使う。
モジュールルートの record、Command と Result、Request と Response は、値オブジェクトではなく Java の標準型を持つ。

## 置き場所と命名

- `com.example.demo.<feature>.domain.model` に置く。
- 名前は値を表すユビキタス言語の名詞にする。
  識別子は対象の名前に `Id` を付け（`OrderId`、`CustomerId`、`PaymentId`）、そのほかは `Money`、`Quantity`、`ProductCode` のようにする。
- 単一の値を持つ record の component は `value` にする（`OrderId(String value)`）。
  金額のように値の意味を名前で示すときは、その名前にする（`Money(BigDecimal amount)`）。
- 固定の値の集合は enum にする（`OrderStatus`、`MembershipRank`）。

## 必須の記述

- `public record` または `public enum` にし、アノテーションを付けない。
- 不変条件は、コンパクトコンストラクタで検査する。
  満たさない値には `IllegalArgumentException` を投げ、メッセージは英語で、値の名前と値を含める。
- 演算は新しい値オブジェクトを返すメソッドにする（`plus`、`minus`、`times`）。
- 新しい識別子の採番は static メソッドにする（`OrderId.newId()`）。
- record、enum、enum の定数、コンパクトコンストラクタ、public メソッドに Javadoc を書く。
- パッケージの `package-info.java` は集約と共有する。

Domain が投げる JDK の例外は、いまは HTTP の 500 になる。
ユースケースがこの例外を 400、404、422 で返す必要があるときは、実装を止めて利用者に確認し、対応づけを新しい ADR で決める。
ステータスコードの使い分けは[HTTPステータスコードの選択](../../web-api/status-codes.md)に、API のエラー契約は [ADR-013](../../adr/ADR-013-standardize-http-api-contracts.md) に従う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じ `domain.model` の値オブジェクトと enum。
- **依存してはいけない型**：Spring、jOOQ、jOOQ の生成型、JPA、Jackson の型、`domain.service`、`application`、モジュールルートの型、集約と Entity、Repository と外部システムのインタフェース。

## 最小の例と典型的な例

最小の例は、注文の識別子の `OrderId` である。

```java
package com.example.demo.order.domain.model;

import java.util.UUID;

/** 注文 ID。 */
public record OrderId(String value) {

  /** 空白だけの ID を拒否する。 */
  public OrderId {
    if (value.isBlank()) {
      throw new IllegalArgumentException("orderId must not be blank");
    }
  }

  /** 新しい注文 ID を採番する。 */
  public static OrderId newId() {
    return new OrderId(UUID.randomUUID().toString());
  }
}
```

典型的な例は、演算を持つ `Money` と、範囲を検査する `Quantity` と、enum の `OrderStatus` である。

```java
/** 0 以上の金額。 */
public record Money(BigDecimal amount) {

  /** 金額 0。 */
  public static final Money ZERO = new Money(BigDecimal.ZERO);

  /** 負の金額を拒否する。 */
  public Money {
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("amount must not be negative: amount=" + amount);
    }
  }

  /** 金額を足す。 */
  public Money plus(final Money other) {
    return new Money(amount.add(other.amount));
  }

  /** 金額を引く。結果が負になると IllegalArgumentException を投げる。 */
  public Money minus(final Money other) {
    return new Money(amount.subtract(other.amount));
  }

  /** 数量を掛ける。 */
  public Money times(final Quantity quantity) {
    return new Money(amount.multiply(BigDecimal.valueOf(quantity.value())));
  }
}
```

```java
/** 1 以上の数量。 */
public record Quantity(int value) {

  /** 1 未満の数量を拒否する。 */
  public Quantity {
    if (value < 1) {
      throw new IllegalArgumentException("quantity must be at least 1: value=" + value);
    }
  }
}
```

```java
/** 注文の状態。 */
public enum OrderStatus {
  /** 受付。 */
  PLACED,
  /** 確定。 */
  CONFIRMED,
  /** 出荷。 */
  SHIPPED,
  /** 取消。 */
  CANCELLED
}
```

## 対応するテスト

Spring を起動しない JUnit のテストを、値オブジェクトと同じパッケージのテストソースに置く（`QuantityTest`、`MoneyTest`）。
不変条件は QuickTheories のプロパティベーステストで、入力の範囲全体について確かめる（[ADR-012](../../adr/ADR-012-adopt-property-based-and-mutation-testing.md)）。

```java
/** 数量の不変条件を検証する。 */
class QuantityTest {

  @Test
  @DisplayName("1 未満の数量は作れない")
  void rejectsQuantityBelowOne() {
    qt().forAll(integers().between(Integer.MIN_VALUE, 0))
        .checkAssert(
            value ->
                assertThatThrownBy(() -> new Quantity(value))
                    .as("value=%d", value)
                    .isInstanceOf(IllegalArgumentException.class));
  }
}
```

`qt` は `org.quicktheories.QuickTheory.qt`、`integers` は `org.quicktheories.generators.SourceDSL.integers` を static import する。

## アンチパターン

- 識別子や金額を、Domain の中でも `String` や `BigDecimal` のまま渡す。
  引数の順序を取り違えても、コンパイルで検出できない。
- 不変条件の検査を CommandHandler や Controller に書き、値オブジェクトは検査しない。
  検査を通らない経路から不正な値が入る。
- 値オブジェクトを class にして setter を付け、作った後で値を変える。
- 値オブジェクトを Command、Result、モジュールルートの record に持たせる。
- 固定の値の集合を `String` の定数で表す。

## 作成時のチェックリスト

- [ ] `domain.model` に置き、Spring、jOOQ、JPA、Jackson に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks］
- [ ] `domain.service`、`application`、モジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `public record` にし、固定の値の集合は `public enum` にする。［自分で点検］
- [ ] 不変条件をコンパクトコンストラクタで検査し、`IllegalArgumentException` を投げる。［自分で点検］
- [ ] 演算は新しい値オブジェクトを返す。［自分で点検］
- [ ] 識別子の名前は対象の名前に `Id` を付け、単一の値の component は `value` にする。［自分で点検］
- [ ] Domain の例外を 400、404、422 で返す必要があるなら、実装を止めて利用者に確認した。［自分で点検］
- [ ] Javadoc を書く。［自分で点検］
- [ ] 不変条件を QuickTheories のプロパティベーステストで確かめる。［自分で点検］
- [ ] `domain.model` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
