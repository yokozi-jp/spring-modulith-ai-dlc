---
type: Convention
title: クラスの役割：jOOQ の Repository
description: infrastructure.persistence に置き、domain.model の Repository を jOOQ で実装するクラスの定義、置き場所と命名、必須の記述（@Repository と DSLContext）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。Repository を jOOQ で実装するとき、SQL を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：jOOQ の Repository

`Jooq<Aggregate>Repository` は、`domain.model` の `<Aggregate>Repository` を jOOQ で実装するクラスであり、`infrastructure.persistence` に package-private で置いて `@Repository` を付ける。
`DSLContext` で集約のテーブルを読み書きし、jOOQ の Record と集約の変換は `<Aggregate>RecordMapper` に任せる。
jOOQ の API と生成型は `infrastructure.persistence` の外に出さない。
役割の決定理由は [ADR-044](../../adr/ADR-044-define-backend-class-roles-and-naming.md) に示す。

## 定義

Domain の [Repository](repository.md) のインタフェースには、DB の技術による実装が要る。
**jOOQ の Repository**（`Jooq<Aggregate>Repository`）は、集約の保存と取り出しを、jOOQ の SQL で実装するクラスである。
集約ルートと子の [Entity](entity.md) を、一つの `save` と一つの取り出しで一緒に読み書きする。

jOOQ の Repository はトランザクションを開かない。
トランザクションは、呼び出す CommandHandler と QueryService が開く。

jOOQ の Repository は業務規則を持たない。
状態の判定は[集約](aggregate.md)が、型の変換は [RecordMapper](record-mapper.md) が担う。

## 置き場所と命名

- `com.example.demo.<feature>.infrastructure.persistence` に置く。
- 名前は `Jooq` と集約の名前と `Repository` をつなげる（`JooqOrderRepository`）。
- jOOQ の生成型は、`com.example.demo.jooq.Tables` のテーブルと `com.example.demo.jooq.tables.records` の Record を使う。

この文書の `ORDERS`、`ORDER_LINES`、`OrdersRecord`、`OrderLinesRecord` は、説明用の仮の生成型である。
`ORDERS` は `ORDER_ID`、`CUSTOMER_ID`、`STATUS`、`DISCOUNT`、`PLACED_AT` の列を、`ORDER_LINES` は `ORDER_ID`、`LINE_NUMBER`、`PRODUCT_CODE`、`QUANTITY`、`UNIT_PRICE` の列を持つとする。
生成型の扱いは[jOOQコード生成物の管理](../../database/jooq-codegen.md)に従う。

## 必須の記述

- `@Repository` を付けた package-private の `class` にし、`final` を付けず、`<Aggregate>Repository` を実装する。
- `DSLContext` を package-private のコンストラクタで受け取り、`private final DSLContext dsl` に持つ。
- メソッドは、Repository のインタフェースのメソッドを `@Override` で実装し、共通の処理だけを private メソッドにする。
- `save` は、集約ルートの行と子の Entity の行をまとめて書く。
- 複数の集約を返すメソッドは、子の行を集約ごとの SQL で読まず、一回の SQL で読む。
- Record と集約の変換は、`<Aggregate>RecordMapper` の static メソッドで行う。
- `@Transactional` を付けない。
- クラス、フィールド、コンストラクタに Javadoc を書く。
- `infrastructure.persistence` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、jOOQ の API（`org.jooq..`）、jOOQ の生成型（`com.example.demo.jooq..`）、同じモジュールの `domain.model` の集約、値オブジェクト、Repository、同じパッケージの `<Aggregate>RecordMapper`、`@Repository`。
- **依存してはいけない型**：`application`、`domain.service`、`presentation.web`、`infrastructure.client` の型、モジュールルートの型、他モジュールの型とテーブル、`@Transactional`。

## 最小の例と典型的な例

最小の例は、`findById` と `save` を実装した `JooqOrderRepository` である。

```java
package com.example.demo.order.infrastructure.persistence;

import static com.example.demo.jooq.Tables.ORDERS;
import static com.example.demo.jooq.Tables.ORDER_LINES;

/** 注文の集約を jOOQ で保存し、取り出す。 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** jOOQ のコンテキストを受け取る。 */
  /* package */ JooqOrderRepository(final DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Order> findById(final OrderId id) {
    return dsl.selectFrom(ORDERS)
        .where(ORDERS.ORDER_ID.eq(id.value()))
        .fetchOptional()
        .map(order -> OrderRecordMapper.toOrder(order, linesOf(List.of(order.getOrderId()))));
  }

  @Override
  public void save(final Order order) {
    final OrdersRecord orderRecord = OrderRecordMapper.toOrdersRecord(order);
    dsl.insertInto(ORDERS)
        .set(orderRecord)
        .onConflict(ORDERS.ORDER_ID)
        .doUpdate()
        .set(orderRecord)
        .execute();
    dsl.deleteFrom(ORDER_LINES).where(ORDER_LINES.ORDER_ID.eq(order.id().value())).execute();
    dsl.batchInsert(OrderRecordMapper.toOrderLinesRecords(order)).execute();
  }

  // findByCustomer と countUnshippedByCustomer は典型的な例に示す。

  private List<OrderLinesRecord> linesOf(final List<String> orderIds) {
    return dsl.selectFrom(ORDER_LINES)
        .where(ORDER_LINES.ORDER_ID.in(orderIds))
        .orderBy(ORDER_LINES.ORDER_ID, ORDER_LINES.LINE_NUMBER)
        .fetch();
  }
}
```

典型的な例は、複数の注文を返す `findByCustomer` と、件数を返す `countUnshippedByCustomer` である（抜粋）。
`findByCustomer` は、明細を一回の SQL で読んで注文ごとに分ける。

```java
@Override
public List<Order> findByCustomer(final CustomerId customerId) {
  final List<OrdersRecord> orders =
      dsl.selectFrom(ORDERS)
          .where(ORDERS.CUSTOMER_ID.eq(customerId.value()))
          .orderBy(ORDERS.PLACED_AT.desc())
          .fetch();
  final Map<String, List<OrderLinesRecord>> lines =
      linesOf(orders.stream().map(OrdersRecord::getOrderId).toList()).stream()
          .collect(Collectors.groupingBy(OrderLinesRecord::getOrderId));
  return orders.stream()
      .map(
          order ->
              OrderRecordMapper.toOrder(order, lines.getOrDefault(order.getOrderId(), List.of())))
      .toList();
}

@Override
public long countUnshippedByCustomer(final CustomerId customerId) {
  return dsl.fetchCount(
      ORDERS,
      ORDERS.CUSTOMER_ID.eq(customerId.value())
          .and(ORDERS.STATUS.in(OrderStatus.PLACED.name(), OrderStatus.CONFIRMED.name())));
}
```

## 対応するテスト

`@DatabaseTest` で、保存してから読み戻す往復を確かめる。
テストは同じ `infrastructure.persistence` パッケージのテストソースに置き、`new JooqOrderRepository(dsl)` でテスト対象を作る。
テストの例は [Repository](repository.md) の「対応するテスト」に示す。
`OrderRecordMapper` の専用のテストは作らず、この往復で確かめる。
ロールバックの扱いは[バックエンドのDBテスト](../testing-database.md)に従う。

## アンチパターン

- jOOQ の Record や `Result` を、Repository の戻り値にして `application` へ渡す。
- 明細の行を、集約ルートと別のメソッドで保存する。
  集約の不変条件を保ったまま保存できない。
- 一覧の取り出しで、注文ごとに明細を読む SQL を発行する。
  注文の件数だけ SQL が増える。
- 変換を Repository のメソッドごとに書き、同じ変換を何か所にも置く。
- Repository に `@Transactional` を付ける。
- Spring Data の Repository のインタフェースを継承する。
- 他モジュールのテーブルを読み書きする。
  他モジュールの情報は、相手の[参照のインタフェース](feature-queries.md)で読む。

## 作成時のチェックリスト

- [ ] `infrastructure.persistence` に置き、名前を `Jooq<Aggregate>Repository` にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories］
- [ ] `@Repository` を付けた型は `infrastructure.persistence` にだけ置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.repositoriesResideInPersistenceAdapters］
- [ ] Domain のインタフェースの実装を `infrastructure` に置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainInterfacesAreImplementedInInfrastructure］
- [ ] jOOQ の API と生成型は `infrastructure.persistence` の中だけで使う。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.databaseTechnologyApisAreOnlyUsedByPersistenceAdapters］
- [ ] `application`、`presentation.web`、`infrastructure.client` に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods］
- [ ] package-private の class にし、`DSLContext` をコンストラクタで受け取る。［自分で点検］
- [ ] 集約ルートと子の Entity をまとめて読み書きし、一覧で集約ごとの SQL を発行しない。［自分で点検］
- [ ] Record と集約の変換を `<Aggregate>RecordMapper` に任せる。［自分で点検］
- [ ] クラス、フィールド、コンストラクタに Javadoc を書く。［自分で点検］
- [ ] `@DatabaseTest` で保存と読み戻しを確かめる。［自分で点検］
- [ ] `infrastructure.persistence` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
