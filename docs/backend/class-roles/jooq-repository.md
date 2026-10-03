---
type: Convention
title: クラスの役割：jOOQ の Repository
description: infrastructure.persistence に置き、domain.model の Repository を jOOQ で実装し、jOOQ と集約の変換も持つクラスの定義、置き場所と命名、必須の記述（@Repository、DSLContext、convertFrom、multiset、Records.mapping、add と update、UPDATE の条件の lock_no と shared の OptimisticLock による楽観的ロック、shared の共通カラムの処理）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。Repository を jOOQ で実装するとき、SQL や列を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：jOOQ の Repository

`Jooq<Aggregate>Repository` は、`domain.model` の `<Aggregate>Repository` を jOOQ で実装するクラスであり、`infrastructure.persistence` に package-private で置いて `@Repository` を付ける。
jOOQ と集約の変換もこのクラスに書き、読み取りは `convertFrom`、`multiset`、`Records.mapping` で列の数と型をコンパイルで検査しながら集約にする。
書き込みは `add` と `update` に分け、`update` は集約ルートを `lock_no` の条件付きの UPDATE で先に更新して更新件数を `shared` の `OptimisticLock` で判定し、共通カラムの値は `shared` の共通処理から受け取る。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

Domain の [Repository](repository.md) のインタフェースには、DB の技術による実装が要る。
**jOOQ の Repository**（`Jooq<Aggregate>Repository`）は、集約の保存と取り出しを、jOOQ の SQL で実装するクラスである。
新しい集約の `add`、既存の集約の `update`、取り出しの SQL で、集約ルートと子の [Entity](entity.md) を一緒に読み書きする。

テーブルの行の形と集約の形は一致しないため、jOOQ の Repository はその変換も持つ。
読み取りでは、`select` に並べた列を `Field.convertFrom` で[値オブジェクト](value-object.md)に変え、`Records.mapping` で集約の static メソッドと Entity のコンストラクタに渡す。
列の数や型が引数と合わないと、コンパイルが失敗する。
変換のための別のクラスは作らない。

`update` は、[PostgreSQL の排他制御](../../database/postgresql-concurrency-control.md)の楽観的ロックを実装する。
集約ルートの UPDATE の条件に、集約の `lockNo()` と一致する `LOCK_NO` を入れ、更新件数を `OptimisticLock.requireUpdated` に渡す。
集約の `lockNo()` は、CommandHandler が画面から受け取った値と比べ済みである（[CommandHandler](command-handler.md)）。
CommandHandler は画面の値と読んだ値を比べ、UPDATE の条件は読んだ値と更新の時点の行の値を比べる。
この二つの比較で、更新の時点の行の `lock_no` と画面から受け取った値が一致することを確かめる。

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
`ORDERS` は `ORDER_ID`、`CUSTOMER_ID`、`STATUS`、`DISCOUNT`、`PLACED_AT`、`LOCK_NO` の列を、`ORDER_LINES` は `ORDER_ID`、`LINE_NUMBER`、`PRODUCT_CODE`、`QUANTITY`、`UNIT_PRICE`、`LOCK_NO` の列を持つとする。
二つのテーブルは、ほかに[共通カラム](../../database/postgresql-common-columns.md)の `CREATED_*`、`UPDATED_*`、`PATCHED_*` を持つ。
`PLACED_AT` の生成型は、[日時とタイムゾーンの規約](../../datetime/timezone-conventions.md)のとおり `Instant` である。
生成型の扱いは[jOOQコード生成物の管理](../../database/jooq-codegen.md)に従う。

`CommonColumns` は `shared.infrastructure.persistence` の共通処理であり（[ADR-048](../../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）、`forInsert(テーブル)` と `forUpdate(テーブル)` は `LOCK_NO` を含む共通カラムの列と値の `Map` を返す。
`forInsert` は `LOCK_NO` を `1` にし、`forUpdate` は `LOCK_NO` を1加算する。
`*_PGM_CD` の値の求め方は [PostgreSQL の共通カラム](../../database/postgresql-common-columns.md)に従う。
`OptimisticLock` も `shared.infrastructure.persistence` の共通処理であり、`requireUpdated(更新件数, テーブル, 主キーの条件, 競合の例外を作る関数)` で楽観的ロックの更新件数を判定する（[ADR-052](../../adr/ADR-052-detect-optimistic-lock-conflicts-by-update-count.md)）。

## 必須の記述

- `@Repository` を付けた package-private の `class` にし、`final` を付けず、`<Aggregate>Repository` を実装する。
- `DSLContext` と `shared` の `CommonColumns`、`OptimisticLock` を package-private のコンストラクタで受け取り、`private final` のフィールドに持つ。
- メソッドは、Repository のインタフェースのメソッドを `@Override` で実装し、共通の処理だけを private メソッドにする。
- SQL は生成されたテーブルと列で組み立て、Plain SQL と `withRenderSchema(false)` を使わない（[jOOQのSQLの書き方](../../database/jooq-usage.md)）。
- 集約を読む SQL の列の選択と変換は、private メソッド `select<Aggregate>s()` 一つに置く。
  取り出しのメソッドは、そこへ `where` と `orderBy` を足す。
- 列は `convertFrom` で値オブジェクトと enum に変える（`ORDERS.ORDER_ID.convertFrom(OrderId::new)`、`ORDERS.STATUS.convertFrom(OrderStatus::valueOf)`）。
- 子の Entity は、`multiset` の副問い合わせで集約ルートと同じ SQL で読み、`convertFrom(lines -> lines.map(Records.mapping(OrderLine::new)))` で Entity のリストにする。
  副問い合わせには、子を識別する列の `orderBy` を付ける。
- `select` に並べる列の順は、`Order.restore` と Entity のコンストラクタの引数の順に合わせる。
  集約ルートの `LOCK_NO` も選び、`restore` の `lockNo` に渡す。
- 集約は `fetchOptional(Records.mapping(Order::restore))` か `fetch(Records.mapping(Order::restore))` で作り、`Order.place` を使わない。
- `add` は、`insertInto(ORDERS)` の `set(列, 値)` で業務の全列を書き、`LOCK_NO` を含む共通カラムを `set(commonColumns.forInsert(ORDERS))` で書く。
  値オブジェクトと enum は、アクセサ（`value()`、`amount()`、`name()`）で列の値に直す。
- `update` は、次の順に書く。
  1. 主キーの条件（`ORDERS.ORDER_ID.eq(order.id().value())`）を作る。
  2. 集約ルートの行の主キー以外の業務の列を書き、`LOCK_NO` の加算を含む共通カラムを `set(commonColumns.forUpdate(ORDERS))` で書き、条件を `where(byId.and(ORDERS.LOCK_NO.eq(order.lockNo())))` にする。
     集約ルートの業務の列が変わらなくても、集約ルートの行を更新する。
  3. 行のロックを `lock_timeout` までに取れないと、Spring Boot の jOOQ の例外の変換が `CannotAcquireLockException` を投げる。
     集約ルートの UPDATE でこれを catch し、原因に付けた `<Aggregate>ConflictException` を投げる。
  4. 更新件数を `optimisticLock.requireUpdated(updated, ORDERS, byId, OrderConflictException::new)` に渡す。
     0 件のとき、行がなければ `NoSuchElementException` が、行があれば `<Aggregate>ConflictException` が投げられる。
  5. 子の行は集約ルートの後で更新し、主キー以外の業務の列と `forUpdate` の値を書く。
- jOOQ の `executeWithOptimisticLocking` と `recordVersionFields` を使わない。
  これらは `UpdatableRecord.store()` でしか働かず、この役割は UPDATE を DSL で書く（[PostgreSQL の排他制御](../../database/postgresql-concurrency-control.md)）。
- 子の Entity の行は、`add` では行ごとの INSERT を、`update` では行ごとの UPDATE を、`dsl.batch` 一つで実行する。
  注文の明細は受付の後に増えも減りもしないため、`update` は明細の行を消さずに UPDATE する。
- 共通カラムのうち、このクラスが参照するのは、集約の復元と比較のために読む `LOCK_NO` だけにし、`LOCK_NO` を書かず、`CREATED_*`、`UPDATED_*`、`PATCHED_*` の列を参照しない（[ADR-048](../../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）。
- `@Transactional` を付けない。
- クラス、フィールド、コンストラクタに Javadoc を書く。
- `infrastructure.persistence` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

`NoSuchElementException` と `<Aggregate>ConflictException` を 404 と 409 の Problem Details にする対応づけは、まだない。
いまはどちらも 500 になり、対応づけは [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) の Neutral のとおり新しい ADR で決める。
ステータスコードの使い分けは[HTTPステータスコードの選択](../../web-api/status-codes.md)と[更新の競合制御](../../web-api/optimistic-locking.md)に従う。
対応づけの作業は [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107) で扱う。

PostgreSQL には MULTISET がなく、jOOQ は `jsonb_agg` による JSON の集約で模倣する。
子の行が数千に及ぶ集約では、二つの SQL に分けて読むほうが速いことがある。
この規約は子の Entity の読み取りを `multiset` に固定する。
速度が足りないと判断したら、二つの SQL に分ける前に実装を止めて利用者に確認する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、jOOQ の API（`org.jooq..`）、jOOQ の生成型（`com.example.demo.jooq..`）、`shared.infrastructure.persistence` の共通処理（`CommonColumns`、`OptimisticLock`）、同じモジュールの `domain.model` の集約、Entity、値オブジェクト、enum、Repository、`<Aggregate>ConflictException`、`@Repository`、`CannotAcquireLockException`。
- **依存してはいけない型**：`application`、`domain.service`、`presentation.web`、`infrastructure.client` の型、モジュールルートの型、他の機能モジュールの型とテーブル、`@Transactional`、対応づけのライブラリ（MapStruct、ModelMapper、Dozer）。

## 最小の例と典型的な例

最小の例は、`findById`、`add`、`update` を実装した `JooqOrderRepository` である。

```java
package com.example.demo.order.infrastructure.persistence;

import static com.example.demo.jooq.Tables.ORDERS;
import static com.example.demo.jooq.Tables.ORDER_LINES;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

import com.example.demo.shared.infrastructure.persistence.CommonColumns;
import com.example.demo.shared.infrastructure.persistence.OptimisticLock;

/** 注文の集約を jOOQ で保存し、取り出す。 */
@Repository
class JooqOrderRepository implements OrderRepository {

  /** SQL を組み立てて実行する jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 共通カラムの値を作る shared の共通処理。 */
  private final CommonColumns commonColumns;

  /** 楽観的ロックの更新件数を判定する shared の共通処理。 */
  private final OptimisticLock optimisticLock;

  /** jOOQ のコンテキストと shared の共通処理を受け取る。 */
  /* package */ JooqOrderRepository(
      final DSLContext dsl,
      final CommonColumns commonColumns,
      final OptimisticLock optimisticLock) {
    this.dsl = dsl;
    this.commonColumns = commonColumns;
    this.optimisticLock = optimisticLock;
  }

  @Override
  public Optional<Order> findById(final OrderId id) {
    return selectOrders()
        .where(ORDERS.ORDER_ID.eq(id.value()))
        .fetchOptional(Records.mapping(Order::restore));
  }

  @Override
  public void add(final Order order) {
    dsl.insertInto(ORDERS)
        .set(ORDERS.ORDER_ID, order.id().value())
        .set(ORDERS.CUSTOMER_ID, order.customerId().value())
        .set(ORDERS.STATUS, order.status().name())
        .set(ORDERS.DISCOUNT, order.discount().amount())
        .set(ORDERS.PLACED_AT, order.placedAt())
        .set(commonColumns.forInsert(ORDERS))
        .execute();
    dsl.batch(
            order.lines().stream()
                .map(
                    line ->
                        dsl.insertInto(ORDER_LINES)
                            .set(ORDER_LINES.ORDER_ID, order.id().value())
                            .set(ORDER_LINES.LINE_NUMBER, line.lineNumber())
                            .set(ORDER_LINES.PRODUCT_CODE, line.productCode().value())
                            .set(ORDER_LINES.QUANTITY, line.quantity().value())
                            .set(ORDER_LINES.UNIT_PRICE, line.unitPrice().amount())
                            .set(commonColumns.forInsert(ORDER_LINES)))
                .toList())
        .execute();
  }

  @Override
  public void update(final Order order) {
    final Condition byId = ORDERS.ORDER_ID.eq(order.id().value());
    final int updated;
    try {
      updated =
          dsl.update(ORDERS)
              .set(ORDERS.CUSTOMER_ID, order.customerId().value())
              .set(ORDERS.STATUS, order.status().name())
              .set(ORDERS.DISCOUNT, order.discount().amount())
              .set(ORDERS.PLACED_AT, order.placedAt())
              .set(commonColumns.forUpdate(ORDERS))
              .where(byId.and(ORDERS.LOCK_NO.eq(order.lockNo())))
              .execute();
    } catch (final CannotAcquireLockException e) {
      throw new OrderConflictException("order is locked: orderId=" + order.id().value(), e);
    }
    optimisticLock.requireUpdated(updated, ORDERS, byId, OrderConflictException::new);
    dsl.batch(
            order.lines().stream()
                .map(
                    line ->
                        dsl.update(ORDER_LINES)
                            .set(ORDER_LINES.PRODUCT_CODE, line.productCode().value())
                            .set(ORDER_LINES.QUANTITY, line.quantity().value())
                            .set(ORDER_LINES.UNIT_PRICE, line.unitPrice().amount())
                            .set(commonColumns.forUpdate(ORDER_LINES))
                            .where(ORDER_LINES.ORDER_ID.eq(order.id().value()))
                            .and(ORDER_LINES.LINE_NUMBER.eq(line.lineNumber())))
                .toList())
        .execute();
  }

  // findByCustomer と countUnshippedByCustomer は典型的な例に示す。

  /** 注文の列と明細を、Order.restore の引数の型と順に選ぶ。 */
  private SelectJoinStep<
          Record7<OrderId, CustomerId, OrderStatus, List<OrderLine>, Money, Instant, Long>>
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
            ORDERS.PLACED_AT,
            ORDERS.LOCK_NO)
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
          .and(
              ORDERS.STATUS.in(
                  OrderStatus.PLACED.name(),
                  OrderStatus.CONFIRMED.name(),
                  OrderStatus.PAID.name())));
}
```

## 対応するテスト

`@DatabaseTest` で、`add` してから読み戻す往復と、`update` してから読み戻す往復を確かめる。
`update` には、競合と行がない場合のテストを別々に書く。
競合のテストは `lockNo` が行と違う集約を渡して `OrderConflictException` を、行がない場合のテストは保存していない集約を渡して `NoSuchElementException` を確かめる。
件数の判定そのものは `shared` の `OptimisticLockTest` が確かめる。
共通カラムの値はテストの検証対象にする（[PostgreSQL の共通カラム](../../database/postgresql-common-columns.md)）。
テストは同じ `infrastructure.persistence` パッケージのテストソースに置き、`new JooqOrderRepository(dsl, commonColumns, optimisticLock)` でテスト対象を作る。
テストの例は [Repository](repository.md) の「対応するテスト」に示す。
列と集約の変換は、この往復で確かめる。
ロールバックの扱いは[バックエンドのDBテスト](../testing-database.md)に従う。

## アンチパターン

- jOOQ の Record や `Result` を、Repository の戻り値にして `application` へ渡す。
- 明細の行を、集約ルートと別のメソッドで保存する。
  集約の不変条件を保ったまま保存できない。
- `INSERT ... ON CONFLICT` の UPSERT で、`add` と `update` を一つの SQL で兼ねる。
  `lock_no` の比較を通らずに上書きし、他の人が消した注文を作り直す。
- `update` で、UPDATE の更新件数を無視する、または `requireUpdated` を使わずに自分で判定する。
  判定を忘れると、他の人の更新を黙って上書きする。
- `update` で、子の行を集約ルートより先に UPDATE する、または集約ルートの業務の列が変わらないときに集約ルートの UPDATE を省く。
  子の行が集約ルートの行ロックで守られず、集約ルートの `lock_no` も進まない。
- `CREATED_*`、`UPDATED_*`、`PATCHED_*` の列に、`Clock` やトレースの値を直接書く。
  共通カラムの値の作り方が Repository ごとに食い違う。
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
- 他の機能モジュールのテーブルを読み書きする。
  他の機能モジュールの情報は、相手の[参照のインタフェース](feature-queries.md)で読む。
- `application` の CommandHandler や QueryService、`domain.service`、モジュールルートの型を使う。
  Presentation のほかに処理の入口ができ、トランザクション境界が Application の外にも広がる。

## 作成時のチェックリスト

- [ ] `infrastructure.persistence` に置き、名前を `Jooq<Aggregate>Repository` にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories］
- [ ] `@Repository` を付けた型は `infrastructure.persistence` にだけ置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.repositoriesResideInPersistenceAdapters］
- [ ] Domain のインタフェースの実装を `infrastructure` に置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainInterfacesAreImplementedInInfrastructure］
- [ ] jOOQ の API と生成型は `infrastructure.persistence` の中だけで使う。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.databaseTechnologyApisAreOnlyUsedByPersistenceAdapters］
- [ ] `presentation.web`、`infrastructure.client` に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `application`、`domain.service`、モジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.infrastructureDependsOnlyOnDomainModel］
- [ ] `shared` の型は `infrastructure.persistence` の中だけで使う。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.sharedModuleIsUsedOnlyByPersistenceAdapters］
- [ ] `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods］
- [ ] package-private の class にし、`DSLContext` と `shared` の `CommonColumns`、`OptimisticLock` をコンストラクタで受け取る。［自分で点検］
- [ ] 列の選択と変換を `select<Aggregate>s()` 一つに置き、列を `convertFrom` で値オブジェクトと enum に変える。［自分で点検］
- [ ] 子の Entity を `multiset` で集約ルートと同じ SQL で読み、集約を `Records.mapping(Order::restore)` で作る。［自分で点検］
- [ ] `multiset` の副問い合わせに、子を識別する列の `orderBy` を付ける（`orderBy(ORDER_LINES.LINE_NUMBER)`）。［自分で点検］
- [ ] 集約ルートの `LOCK_NO` を選び、`restore` の `lockNo` に渡す。［自分で点検］
- [ ] `add` は `set(列, 値)` で業務の全列を書き、子の行を行ごとの INSERT の `dsl.batch` 一つで書く。［自分で点検］
- [ ] `update` は、集約ルートを先に `LOCK_NO.eq(order.lockNo())` の条件で UPDATE して更新件数を `optimisticLock.requireUpdated` に渡し、その後で子の行を UPDATE する。［自分で点検］
- [ ] 集約ルートの UPDATE の `CannotAcquireLockException` を、原因に付けた `<Aggregate>ConflictException` にする。［自分で点検］
- [ ] jOOQ の `executeWithOptimisticLocking` と `recordVersionFields` を使わない。［自分で点検］
- [ ] `add` と `update` で、UPSERT（`INSERT ... ON CONFLICT`）を使わない。［自分で点検］
- [ ] `LOCK_NO` を含む共通カラムは `CommonColumns` の `forInsert` と `forUpdate` で書き、`LOCK_NO` は読むだけにし、`CREATED_*`、`UPDATED_*`、`PATCHED_*` の列を参照しない。［自分で点検］
- [ ] Plain SQL と `withRenderSchema(false)` を使わない。［自分で点検］
- [ ] 変換で `Order.place`、業務規則、既定値、Mapper のクラス、次の ArchUnit の規則が検査しないリフレクションの対応づけを使わない。［自分で点検］
- [ ] jOOQ の `into`、`intoMap`、`intoGroups`、`fetchMap`、`fetchGroups`、名前が `Into` で終わるメソッドを `Class` を渡して呼ばず、`Record` の `into(Object)` と `from(Object)`、`DSLContext.newRecord(Table, Object)` を呼ばない。［ArchUnit で検査：ClassRoleArchTest.jooqReflectionMappingIsNotUsed］
- [ ] MapStruct、ModelMapper、Dozer、`DefaultRecordMapper`、`DefaultRecordUnmapper` に依存しない。［ArchUnit で検査：ClassRoleArchTest.mappingLibrariesAreNotUsed］
- [ ] クラス、フィールド、コンストラクタに Javadoc を書く。［自分で点検］
- [ ] `@DatabaseTest` で `add` と `update` の往復を確かめ、`update` の競合のテスト（`<Aggregate>ConflictException`）と行がないテスト（`NoSuchElementException`）を書く。［自分で点検］
- [ ] `infrastructure.persistence` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
