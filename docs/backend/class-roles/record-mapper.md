---
type: Convention
title: クラスの役割：RecordMapper
description: infrastructure.persistence に置き、jOOQ の生成型の Record と集約を変換する RecordMapper の定義、置き場所と命名、必須の記述（final と static メソッド）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。jOOQ の Record と集約を変換するとき、列や項目を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：RecordMapper

`<Aggregate>RecordMapper` は、jOOQ の生成型の Record と集約を相互に変換するクラスであり、`infrastructure.persistence` に package-private の `final` クラスとして置く。
変換は static メソッドで書き、Record から集約を作るときは `Order.restore` を使う。
専用のテストは作らず、`Jooq<Aggregate>Repository` の往復のテストで確かめる。
役割の決定理由は [ADR-044](../../adr/ADR-044-define-backend-class-roles-and-naming.md) に示す。

## 定義

テーブルの行の形と集約の形は一致しない。
**RecordMapper**（`<Aggregate>RecordMapper`）は、その違いを一か所で埋める、状態を持たない変換のクラスである。
同じパッケージの [jOOQ の Repository](jooq-repository.md) だけが使う。

RecordMapper は業務規則を持たない。
状態を変えず、値の検証は[値オブジェクト](value-object.md)と `Order.restore` に任せる。

RecordMapper は Presentation の変換でもない。
Request と Response の変換は、`toCommand()` と `from(...)` が担う。

## 置き場所と命名

- `com.example.demo.<feature>.infrastructure.persistence` に置く。
- 名前は集約の名前に `RecordMapper` を付ける（`OrderRecordMapper`）。
- メソッドの名前は、変換先の型の名前に `to` を付ける（`toOrder`、`toOrdersRecord`、`toOrderLinesRecords`）。

## 必須の記述

- package-private の `final class` にし、private のコンストラクタを置き、アノテーションを付けない。
- 変換のメソッドはすべて package-private の `static` にする。
- Record から集約を作るときは `Order.restore` を使い、`Order.place` を使わない。
  文字列の状態は `OrderStatus.valueOf` で enum に直す。
- 集約から Record を作るときは、生成型の Record のコンストラクタで全列を埋める。
- クラスと package-private のメソッドに Javadoc を書く。
- `infrastructure.persistence` のパッケージの `package-info.java` は jOOQ の Repository と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、jOOQ の生成型の Record、同じモジュールの `domain.model` の集約、Entity、値オブジェクト、enum。
- **依存してはいけない型**：`DSLContext` などの SQL を実行する jOOQ の API、Spring の型、`application`、`domain.service`、`presentation.web`、`infrastructure.client` の型、モジュールルートの型、他モジュールの型。

## 最小の例と典型的な例

`OrderRecordMapper` の全体を、最小の例と典型的な例を兼ねて示す。
生成型は [jOOQ の Repository](jooq-repository.md) で説明した仮の型である。

```java
package com.example.demo.order.infrastructure.persistence;

/** 注文の集約と、注文と明細のテーブルの Record を変換する。 */
final class OrderRecordMapper {

  private OrderRecordMapper() {}

  /** 注文と明細の Record から、保存済みの注文を復元する。 */
  /* package */ static Order toOrder(final OrdersRecord order, final List<OrderLinesRecord> lines) {
    return Order.restore(
        new OrderId(order.getOrderId()),
        new CustomerId(order.getCustomerId()),
        OrderStatus.valueOf(order.getStatus()),
        lines.stream().map(OrderRecordMapper::toOrderLine).toList(),
        new Money(order.getDiscount()),
        order.getPlacedAt());
  }

  /** 注文の Record を作る。 */
  /* package */ static OrdersRecord toOrdersRecord(final Order order) {
    return new OrdersRecord(
        order.id().value(),
        order.customerId().value(),
        order.status().name(),
        order.discount().amount(),
        order.placedAt());
  }

  /** 明細の Record を作る。 */
  /* package */ static List<OrderLinesRecord> toOrderLinesRecords(final Order order) {
    return order.lines().stream()
        .map(
            line ->
                new OrderLinesRecord(
                    order.id().value(),
                    line.lineNumber(),
                    line.productCode().value(),
                    line.quantity().value(),
                    line.unitPrice().amount()))
        .toList();
  }

  private static OrderLine toOrderLine(final OrderLinesRecord line) {
    return new OrderLine(
        line.getLineNumber(),
        new ProductCode(line.getProductCode()),
        new Quantity(line.getQuantity()),
        new Money(line.getUnitPrice()));
  }
}
```

`JooqOrderRepository` は、読んだ Record を `toOrder` に渡し、保存する前に `toOrdersRecord` と `toOrderLinesRecords` で Record を作る。

```java
// com.example.demo.order.infrastructure.persistence.JooqOrderRepository（抜粋）
final OrdersRecord orderRecord = OrderRecordMapper.toOrdersRecord(order);
dsl.batchInsert(OrderRecordMapper.toOrderLinesRecords(order)).execute();
```

## 対応するテスト

専用のテストは作らない。
`Jooq<Aggregate>Repository` を `@DatabaseTest` で、保存してから読み戻す往復で確かめる。
テストの例は [Repository](repository.md) の「対応するテスト」に示す。

## アンチパターン

- Record から集約を作るときに `Order.place` を使う。
  保存済みの状態と割引額が、受付の初期値に戻る。
- 変換の中で、業務規則や既定値を補う。
- RecordMapper を Spring の Bean にする、またはインスタンスのメソッドにする。
- 列と項目をリフレクションで自動で対応づける。
  列や項目の名前を変えたときの誤りが、コンパイルで見つからない。
- Record を `infrastructure.persistence` の外へ渡す。

## 作成時のチェックリスト

- [ ] `infrastructure.persistence` に置き、jOOQ の生成型をこのパッケージの外へ出さない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.databaseTechnologyApisAreOnlyUsedByPersistenceAdapters］
- [ ] `presentation.web`、`infrastructure.client` に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `application`、`domain.service`、モジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModel］
- [ ] package-private の `final class` にし、private のコンストラクタと static メソッドだけを持つ。［自分で点検］
- [ ] 名前を集約の名前に `RecordMapper` を付けた形にする。［自分で点検］
- [ ] Record から集約を作るときは `restore` を使う。［自分で点検］
- [ ] 業務規則と既定値を持たない。［自分で点検］
- [ ] クラスと package-private のメソッドに Javadoc を書く。［自分で点検］
- [ ] jOOQ の Repository の往復のテストで変換を確かめる。［自分で点検］
- [ ] `infrastructure.persistence` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
