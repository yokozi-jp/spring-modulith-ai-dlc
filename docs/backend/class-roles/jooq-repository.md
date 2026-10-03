---
type: Convention
title: クラスの役割：jOOQ の Repository
description: infrastructure.persistence に置き、domain.model の Repository を jOOQ で実装し、jOOQ と集約の変換も持つクラスの定義、置き場所と命名、必須の記述（@Repository、DSLContext、convertFrom、multiset、Records.mapping）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。Repository を jOOQ で実装するとき、SQL や列を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：jOOQ の Repository

`Jooq<Aggregate>Repository` は、`domain.model` の `<Aggregate>Repository` を jOOQ で実装するクラスであり、`infrastructure.persistence` に package-private で置いて `@Repository` を付ける。
jOOQ と集約の変換もこのクラスに書き、読み取りは `convertFrom`、`multiset`、`Records.mapping` で列の数と型をコンパイルで検査しながら集約にする。
jOOQ の API と生成型は `infrastructure.persistence` の外に出さない。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

Domain の [Repository](repository.md) のインタフェースには、DB の技術による実装が要る。
**jOOQ の Repository**（`Jooq<Aggregate>Repository`）は、集約の保存と取り出しを、jOOQ の SQL で実装するクラスである。
集約ルートと子の [Entity](entity.md) を、一つの `save` と一つの取り出しの SQL で一緒に読み書きする。

テーブルの行の形と集約の形は一致しないため、jOOQ の Repository はその変換も持つ。
読み取りでは、`select` に並べた列を `Field.convertFrom` で[値オブジェクト](value-object.md)に変え、`Records.mapping` で集約の static メソッドと Entity のコンストラクタに渡す。
列の数や型が引数と合わないと、コンパイルが失敗する。
変換のための別のクラスは作らない。

jOOQ の Repository はトランザクションを開かない。
トランザクションは、呼び出す CommandHandler と QueryService が開く。

jOOQ の Repository は業務規則を持たない。
状態の判定は[集約](aggregate.md)が、値の検証は値オブジェクトと `Order.restore` が担う。

## 置き場所と命名

- `com.example.demo.<feature>.infrastructure.persistence` に置く。
- 名前は `Jooq` と集約の名前と `Repository` をつなげる（`JooqOrderRepository`）。
- 集約を読む SQL の列の選択と変換を置く private メソッドは、`select` に集約の名前の複数形を付ける（`selectOrders`）。
- jOOQ の生成型は、`com.example.demo.jooq.Tables` のテーブルと列を使う。

この文書の `ORDERS`、`ORDER_LINES` と、その Record の `OrdersRecord`、`OrderLinesRecord` は、説明用の仮の生成型である。
`ORDERS` は `ORDER_ID`、`CUSTOMER_ID`、`STATUS`、`DISCOUNT`、`PLACED_AT` の列を、`ORDER_LINES` は `ORDER_ID`、`LINE_NUMBER`、`PRODUCT_CODE`、`QUANTITY`、`UNIT_PRICE` の列を持つとする。
`PLACED_AT` の生成型は、[日時とタイムゾーンの規約](../../datetime/timezone-conventions.md)のとおり `Instant` である。
生成型の扱いは[jOOQコード生成物の管理](../../database/jooq-codegen.md)に従う。

## 必須の記述

- `@Repository` を付けた package-private の `class` にし、`final` を付けず、`<Aggregate>Repository` を実装する。
- `DSLContext` を package-private のコンストラクタで受け取り、`private final DSLContext dsl` に持つ。
- メソッドは、Repository のインタフェースのメソッドを `@Override` で実装し、共通の処理だけを private メソッドにする。
- 集約を読む SQL の列の選択と変換は、private メソッド `select<Aggregate>s()` 一つに置く。
  取り出しのメソッドは、そこへ `where` と `orderBy` を足す。
- 列は `convertFrom` で値オブジェクトと enum に変える（`ORDERS.ORDER_ID.convertFrom(OrderId::new)`、`ORDERS.STATUS.convertFrom(OrderStatus::valueOf)`）。
- 子の Entity は、`multiset` の副問い合わせで集約ルートと同じ SQL で読み、`convertFrom(lines -> lines.map(Records.mapping(OrderLine::new)))` で Entity のリストにする。
  副問い合わせには、子を識別する列の `orderBy` を付ける。
- `select` に並べる列の順は、`Order.restore` と Entity のコンストラクタの引数の順に合わせる。
- 集約は `fetchOptional(Records.mapping(Order::restore))` か `fetch(Records.mapping(Order::restore))` で作り、`Order.place` を使わない。
- 書き込みは、`insertInto(ORDERS)` の `set(列, 値)` で全列を書き、`onConflict(主キー).doUpdate().setNonConflictingKeyToExcluded()` で既存の行を更新する。
  値オブジェクトと enum は、アクセサ（`value()`、`amount()`、`name()`）で列の値に直す。
- 子の Entity の行は、集約の子の行を消してから、`valuesOfRows` の複数行の INSERT 一つで書く。
- `@Transactional` を付けない。
- クラス、フィールド、コンストラクタに Javadoc を書く。
- `infrastructure.persistence` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

PostgreSQL には MULTISET がなく、jOOQ は `jsonb_agg` による JSON の集約で模倣する。
子の行が数千に及ぶ集約では、二つの SQL に分けて読むほうが速いことがある。
この規約は子の Entity の読み取りを `multiset` に固定する。
速度が足りないと判断したら、二つの SQL に分ける前に実装を止めて利用者に確認する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、jOOQ の API（`org.jooq..`）、jOOQ の生成型（`com.example.demo.jooq..`）、同じモジュールの `domain.model` の集約、Entity、値オブジェクト、enum、Repository、`@Repository`。
- **依存してはいけない型**：`application`、`domain.service`、`presentation.web`、`infrastructure.client` の型、モジュールルートの型、他モジュールの型とテーブル、`@Transactional`、対応づけのライブラリ（MapStruct、ModelMapper、Dozer）。

## 最小の例と典型的な例

最小の例は、`findById` と `save` を実装した `JooqOrderRepository` である。

```java
package com.example.demo.order.infrastructure.persistence;

import static com.example.demo.jooq.Tables.ORDERS;
import static com.example.demo.jooq.Tables.ORDER_LINES;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;

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
    return selectOrders()
        .where(ORDERS.ORDER_ID.eq(id.value()))
        .fetchOptional(Records.mapping(Order::restore));
  }

  @Override
  public void save(final Order order) {
    dsl.insertInto(ORDERS)
        .set(ORDERS.ORDER_ID, order.id().value())
        .set(ORDERS.CUSTOMER_ID, order.customerId().value())
        .set(ORDERS.STATUS, order.status().name())
        .set(ORDERS.DISCOUNT, order.discount().amount())
        .set(ORDERS.PLACED_AT, order.placedAt())
        .onConflict(ORDERS.ORDER_ID)
        .doUpdate()
        .setNonConflictingKeyToExcluded()
        .execute();
    dsl.deleteFrom(ORDER_LINES).where(ORDER_LINES.ORDER_ID.eq(order.id().value())).execute();
    dsl.insertInto(
            ORDER_LINES,
            ORDER_LINES.ORDER_ID,
            ORDER_LINES.LINE_NUMBER,
            ORDER_LINES.PRODUCT_CODE,
            ORDER_LINES.QUANTITY,
            ORDER_LINES.UNIT_PRICE)
        .valuesOfRows(
            order.lines().stream()
                .map(
                    line ->
                        row(
                            order.id().value(),
                            line.lineNumber(),
                            line.productCode().value(),
                            line.quantity().value(),
                            line.unitPrice().amount()))
                .toList())
        .execute();
  }

  // findByCustomer と countUnshippedByCustomer は典型的な例に示す。

  /** 注文の列と明細を、Order.restore の引数の型と順に選ぶ。 */
  private SelectJoinStep<Record6<OrderId, CustomerId, OrderStatus, List<OrderLine>, Money, Instant>>
      selectOrders() {
    return dsl.select(
            ORDERS.ORDER_ID.convertFrom(OrderId::new),
            ORDERS.CUSTOMER_ID.convertFrom(CustomerId::new),
            ORDERS.STATUS.convertFrom(OrderStatus::valueOf),
            multiset(
                    select(
                            ORDER_LINES.LINE_NUMBER,
                            ORDER_LINES.PRODUCT_CODE.convertFrom(ProductCode::new),
                            ORDER_LINES.QUANTITY.convertFrom(Quantity::new),
                            ORDER_LINES.UNIT_PRICE.convertFrom(Money::new))
                        .from(ORDER_LINES)
                        .where(ORDER_LINES.ORDER_ID.eq(ORDERS.ORDER_ID))
                        .orderBy(ORDER_LINES.LINE_NUMBER))
                .convertFrom(lines -> lines.map(Records.mapping(OrderLine::new))),
            ORDERS.DISCOUNT.convertFrom(Money::new),
            ORDERS.PLACED_AT)
        .from(ORDERS);
  }
}
```

典型的な例は、複数の注文を返す `findByCustomer` と、件数を返す `countUnshippedByCustomer` である（抜粋）。
`findByCustomer` は `selectOrders` に条件と並び順を足し、明細も同じ SQL で読む。

```java
@Override
public List<Order> findByCustomer(final CustomerId customerId) {
  return selectOrders()
      .where(ORDERS.CUSTOMER_ID.eq(customerId.value()))
      .orderBy(ORDERS.PLACED_AT.desc())
      .fetch(Records.mapping(Order::restore));
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
列と集約の変換は、この往復で確かめる。
ロールバックの扱いは[バックエンドのDBテスト](../testing-database.md)に従う。

## アンチパターン

- jOOQ の Record や `Result` を、Repository の戻り値にして `application` へ渡す。
- 明細の行を、集約ルートと別のメソッドで保存する。
  集約の不変条件を保ったまま保存できない。
- 一覧の取り出しで、注文ごとに明細を読む SQL を発行する。
  注文の件数だけ SQL が増える。
- 列の選択と変換を取り出しのメソッドごとに書き、同じ変換を何か所にも置く。
- 集約を作るときに `Order.place` を使う。
  保存済みの状態と割引額が、受付の初期値に戻る。
- 変換の中で、業務規則を判定する、または既定値を補う。
- `into(Class)`、`fetchInto(Class)`、`fetchMap(Field, Class)`、`from(Object)`、`DefaultRecordMapper`、ModelMapper、Dozer で、列と項目を名前のリフレクションで対応づける。
  列や項目の名前を変えたときの誤りが、コンパイルで見つからない。
  集約が `Order.restore` と値オブジェクトの検証を通らず、private のフィールドへ直接書き込まれることがある。
  書き込みの `from(Object)` では、ドメインのフィールド名を変えると、その列だけが例外を出さずに保存されなくなる。
  理由の詳細は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) の選択肢14に示す。
- 変換を別の Mapper のクラスや MapStruct に切り出す。
- jOOQ のコード生成の `forcedTypes` と `Converter` で、列を Domain の値オブジェクトに対応づける。
  共有の生成パッケージ `com.example.demo.jooq` が、モジュールの Domain の型に依存する。
- Repository に `@Transactional` を付ける。
- Spring Data の Repository のインタフェースを継承する。
- 他モジュールのテーブルを読み書きする。
  他モジュールの情報は、相手の[参照のインタフェース](feature-queries.md)で読む。
- `application` の CommandHandler や QueryService、`domain.service`、モジュールルートの型を使う。
  Presentation のほかに処理の入口ができ、トランザクション境界が Application の外にも広がる。

## 作成時のチェックリスト

- [ ] `infrastructure.persistence` に置き、名前を `Jooq<Aggregate>Repository` にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories］
- [ ] `@Repository` を付けた型は `infrastructure.persistence` にだけ置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.repositoriesResideInPersistenceAdapters］
- [ ] Domain のインタフェースの実装を `infrastructure` に置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainInterfacesAreImplementedInInfrastructure］
- [ ] jOOQ の API と生成型は `infrastructure.persistence` の中だけで使う。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.databaseTechnologyApisAreOnlyUsedByPersistenceAdapters］
- [ ] `presentation.web`、`infrastructure.client` に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `application`、`domain.service`、モジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModel］
- [ ] `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods］
- [ ] package-private の class にし、`DSLContext` をコンストラクタで受け取る。［自分で点検］
- [ ] 列の選択と変換を `select<Aggregate>s()` 一つに置き、列を `convertFrom` で値オブジェクトと enum に変える。［自分で点検］
- [ ] 子の Entity を `multiset` で集約ルートと同じ SQL で読み、集約を `Records.mapping(Order::restore)` で作る。［自分で点検］
- [ ] `multiset` の副問い合わせに、子を識別する列の `orderBy` を付ける（`orderBy(ORDER_LINES.LINE_NUMBER)`）。［自分で点検］
- [ ] 書き込みは `set(列, 値)` で全列を書き、子の行を `valuesOfRows` の INSERT 一つで書く。［自分で点検］
- [ ] 変換で `Order.place`、業務規則、既定値、Mapper のクラス、次の ArchUnit の規則が検査しないリフレクションの対応づけを使わない。［自分で点検］
- [ ] jOOQ の `into`、`intoMap`、`intoGroups`、`fetchMap`、`fetchGroups`、名前が `Into` で終わるメソッドを `Class` を渡して呼ばず、`Record` の `into(Object)` と `from(Object)`、`DSLContext.newRecord(Table, Object)` を呼ばない。［ArchUnit で検査：ClassRoleArchTest.jooqReflectionMappingIsNotUsed］
- [ ] MapStruct、ModelMapper、Dozer、`DefaultRecordMapper`、`DefaultRecordUnmapper` に依存しない。［ArchUnit で検査：ClassRoleArchTest.mappingLibrariesAreNotUsed］
- [ ] クラス、フィールド、コンストラクタに Javadoc を書く。［自分で点検］
- [ ] `@DatabaseTest` で保存と読み戻しを確かめる。［自分で点検］
- [ ] `infrastructure.persistence` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
