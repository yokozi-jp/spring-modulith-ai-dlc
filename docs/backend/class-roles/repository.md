---
type: Convention
title: クラスの役割：Repository
description: domain.model に置く集約の Repository インタフェースの定義、置き場所と命名、必須の記述（新規の add、更新の update、物理削除の delete、件数を返すメソッドの @CheckReturnValue）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。集約を保存し取り出すインタフェースを作るとき、Repository にメソッドを足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Repository

`<Aggregate>Repository` は、集約ルートを保存し、取り出すインタフェースであり、`domain.model` に置く。
集約ルートごとに一つ作り、メソッドはドメインの語彙で名付ける。
保存は、新しい集約の `add` と、既存の集約の `update` に分ける。
実装は `infrastructure.persistence` の `Jooq<Aggregate>Repository` が持つ。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

Domain と Application は、DB の技術に依存せずに集約を保存し、取り出す必要がある。
**`<Aggregate>Repository`** は、そのための操作を集約の語彙で定めるインタフェースである。
インタフェースを Domain に置き、実装を Infrastructure に置くことで、依存は内向きに保たれる。

Repository は、集約ルート（[集約](aggregate.md)）だけを扱う。
集約の中の [Entity](entity.md) は、集約ルートと一緒に保存し、取り出す。

Repository は、テーブルごとの DAO ではない。
SQL、テーブル、列の都合はインタフェースに出さない。

`<UseCase>CommandHandler`、`<Feature>QueryService`、[Domain Service](domain-service.md) が Repository を使う。
Presentation は Repository を使わない。

## 置き場所と命名

- 集約と同じ `com.example.demo.<feature>.domain.model` に置く。
- 名前は集約ルートの名前に `Repository` を付ける（`OrderRepository`）。
- 1件の取り出しは `findById`、条件での取り出しは `findBy<条件>`、件数は `count<条件>`、新しい ID の採番は `nextId`、新しい集約の保存は `add`、既存の集約の保存は `update` にする。
  条件の名前は業務の語にする（`findByCustomer`、`countUnshippedByCustomer`）。
- 実装は `infrastructure.persistence` の `Jooq<Aggregate>Repository` にする（`JooqOrderRepository`）。

## 必須の記述

- `public interface` にし、アノテーションを付けない。
- 引数と戻り値は、集約ルート、値オブジェクト、Java の標準型にする。
- 主キーが UUID のテーブルの集約だけが、新しい集約の ID を `OrderId nextId()` で採番する。
  実装は `infrastructure.persistence` の `Jooq<Aggregate>Repository` に置き、DB の DEFAULT では採番しない（[PostgreSQLの主キー](../../database/postgresql-primary-keys.md#uuidの採番)）。
- 1件の取り出しは `Optional<集約ルート>` を、複数の取り出しは `List<集約ルート>` を返す。
- 新しい集約の保存は `void add(集約ルート)` に、既存の集約の保存は `void update(集約ルート)` にする。
  新規と更新を一つの `save` にしない。
- `update` は、[PostgreSQL の排他制御](../../database/postgresql-concurrency-control.md)の楽観的ロックで保存する。
  集約の行がなければ `NoSuchElementException` を、更新の時点の行の `lock_no` が集約の `lockNo()` と違うか `lock_timeout` までに行のロックを取れなければ `shared.concurrency` の `ConflictException` を投げる。
  この二つの例外は、実装が使う `shared` の `TableWriter` が投げる（[jOOQ の Repository](jooq-repository.md)）。
  この二つの例外を、インタフェースの Javadoc に書く。
- 集約の物理削除が要るときは `void delete(集約ルート)` を置き、`update` と同じ二つの例外を Javadoc に書く。
  期待する版を持つ書き込みは、集約ルートを受け取る `update` と `delete` に限り、識別子と版を受け取るメソッドを作らない。
- `add`、`update`、`delete` の引数の集約ルートは、引数のない `long lockNo()` を持つ（[集約](aggregate.md)）。
- `update` と `delete` を呼べるのは CommandHandler だけである。
  Domain Service と QueryService は Repository を読み取りにだけ使う。
  CommandHandler が集約の `ensureLockNo` で画面の版を比べてから保存する流れを、迂回させないためである。
- 版を比べない UPDATE か DELETE の件数を返すメソッド（在庫を引き当てて件数を返す、など）には、Error Prone の `@CheckReturnValue` を付ける。
  呼び出し側が件数を捨てると、コンパイルが失敗する。
- インタフェースと各メソッドに Javadoc を書く。
- パッケージの `package-info.java` は集約と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、`com.google.errorprone.annotations.CheckReturnValue`、同じ `domain.model` の集約ルート、値オブジェクト、enum、`shared.concurrency` の `ConflictException`（Javadoc の `@throws` のため）。
- **依存してはいけない型**：jOOQ の API と生成型（`DSLContext`、`Condition`、説明用の仮の生成型 `OrdersRecord`）、Spring の型（`Pageable`、`@Repository`）、JPA と Jackson の型、`application`、モジュールルートの型（参照の結果、検索条件）。

## 最小の例と典型的な例

最小の例は、ID で取り出し、新しい注文と既存の注文を保存する `OrderRepository` である。

```java
package com.example.demo.order.domain.model;

import com.example.demo.shared.concurrency.ConflictException;
import java.util.Optional;

/** 注文の集約を保存し、取り出す。 */
public interface OrderRepository {

  /** 新しい注文の ID を採番する。 */
  OrderId nextId();

  /** ID で注文を探す。 */
  Optional<Order> findById(OrderId id);

  /** 新しい注文を保存する。 */
  void add(Order order);

  /**
   * 既存の注文を保存する。
   *
   * @throws NoSuchElementException 注文の行がないとき
   * @throws ConflictException 更新の時点の注文の行のロック番号が order と違うとき、または lock_timeout までに行をロックできないとき
   */
  void update(Order order);
}
```

典型的な例は、参照と Domain Service のためのメソッドを足した `OrderRepository` と、その実装の宣言である。

```java
/** 注文の集約を保存し、取り出す。 */
public interface OrderRepository {

  /** 新しい注文の ID を採番する。 */
  OrderId nextId();

  /** ID で注文を探す。 */
  Optional<Order> findById(OrderId id);

  /** 顧客の注文を返す。 */
  List<Order> findByCustomer(CustomerId customerId);

  /** 顧客の未出荷の注文を数える。 */
  long countUnshippedByCustomer(CustomerId customerId);

  /** 新しい注文を保存する。 */
  void add(Order order);

  /** 既存の注文を保存する。Javadoc の例外は最小の例と同じである。 */
  void update(Order order);
}
```

```java
// com.example.demo.order.infrastructure.persistence.JooqOrderRepository（宣言だけ）
@Repository
class JooqOrderRepository implements OrderRepository {
  // DSLContext と shared の CommonColumns、TableWriter を受け取り、jOOQ の列と Order の変換もこのクラスに書く。
}
```

## 対応するテスト

インタフェース自体の専用のテストは作らない。
実装の `JooqOrderRepository` を `@DatabaseTest` で、保存してから読み戻す往復で確かめる。
`update` と `delete` の競合と行がない場合は、Repository ごとには確かめず、`shared` の `TableWriterTest` と `TableWriterConcurrencyTest` が確かめる（[jOOQ の Repository](jooq-repository.md) の「対応するテスト」）。
jOOQ の列と集約の変換も、この往復で確かめる。
テストは実装と同じ `com.example.demo.order.infrastructure.persistence` パッケージのテストソースに置く。

```java
/** 注文の保存と読み戻しを検証する。 */
@DatabaseTest
class JooqOrderRepositoryTest {

  /** テスト対象が使う jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** テスト対象が使う shared の共通処理。 */
  @Autowired private CommonColumns commonColumns;

  @Test
  @DisplayName("保存した注文を ID で読み戻せる")
  void savesAndFindsOrder() {
    final OrderRepository repository =
        new JooqOrderRepository(dsl, commonColumns, new TableWriter(dsl, commonColumns));
    final Order order =
        Order.place(
            repository.nextId(),
            new CustomerId("C-1"),
            List.of(
                new OrderLine(
                    1, new ProductCode("P-1"), new Quantity(2), new Money(new BigDecimal("500")))),
            Instant.parse("2026-10-03T00:00:00Z"));
    repository.add(order);

    final Order found =
        repository
            .findById(order.id())
            .orElseThrow(
                () -> new AssertionError("order が見つからない: orderId=" + order.id().value()));
    assertThat(found.total())
        .as("orderId=%s の保存往復", order.id().value())
        .isEqualTo(order.total());
  }
}
```

ロールバックとコミットの扱いは[バックエンドのDBテスト](../testing-database.md)に従う。

## アンチパターン

- Entity ごとの Repository（`OrderLineRepository`）を作り、集約の一部だけを保存する。
  集約の不変条件を保ったまま保存できない。
- メソッドを SQL の語彙で名付ける（`selectByStatusNotAndCustomerId`、`insert`、`upsert`）。
- 新規と更新を `save` 一つで兼ねる。
  実装が行の有無で INSERT と UPDATE を選び、他の人が消した集約の更新が新しい行の作成になる。
- 引数や戻り値に jOOQ の Record、`Condition`、Spring の `Pageable` を使う。
- 画面ごとの射影（参照の結果の record）を Repository が返す。
  参照の結果への変換は `<Feature>QueryService` が担う。
- インタフェースを `application` や `infrastructure` に置く。

## 作成時のチェックリスト

- [ ] `domain.model` に置き、jOOQ、Spring、JPA、Jackson に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainModelDoesNotDependOnFrameworks］
- [ ] `application` とモジュールルートの型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] 集約ルートごとに一つ、`<Aggregate>Repository` の名前で作る。［自分で点検］
- [ ] 主キーが UUID のテーブルの集約では、`nextId()` を持つ。［自分で点検］
- [ ] メソッドはドメインの語彙で名付け、`findById` は `Optional`、複数は `List` を返し、保存は新しい集約の `add` と既存の集約の `update` に分ける。［自分で点検］
- [ ] `update` の Javadoc に、行がないときの `NoSuchElementException` と、競合したときの `ConflictException` を書く。［自分で点検］
- [ ] `add`、`update`、`delete` は、引数のない `long lockNo()` を持つ集約ルートを一つだけ受け取る。［ArchUnit で検査：TableWriterArchTest.repositoryWritesTakeVersionedAggregates］
- [ ] `update` と `delete` を CommandHandler だけが呼ぶ。［ArchUnit で検査：TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregates］
- [ ] 版を比べない UPDATE か DELETE の件数を返すメソッドに `@CheckReturnValue` を付ける。［自分で点検］
- [ ] 実装は `infrastructure.persistence` の `Jooq<Aggregate>Repository` にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.repositoryImplementationsAreJooqRepositories］
- [ ] Domain の外の実装は `infrastructure` に置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainInterfacesAreImplementedInInfrastructure］
- [ ] Controller から Repository を使わない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain］
- [ ] インタフェースと各メソッドに Javadoc を書く。［自分で点検］
- [ ] 実装の保存と読み戻しを `@DatabaseTest` で確かめる。［自分で点検］
- [ ] `domain.model` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
