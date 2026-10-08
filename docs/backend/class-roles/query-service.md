---
type: Convention
title: クラスの役割：QueryService
description: application に置き、参照のインタフェースを実装する QueryService の定義、置き場所と命名、必須の記述（readOnly のトランザクション）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。参照のインタフェースを実装するとき、参照のメソッドを足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：QueryService

`<Feature>QueryService` は、モジュールルートの `<Feature>Queries` を実装するクラスであり、`application` に package-private で置いて `@Service` を付ける。
public メソッドは `<Feature>Queries` のメソッドだけにし、すべてに `@Transactional(readOnly = true)` を付ける。
`<Aggregate>Repository` で読んだ集約を、ルートの record に変換して返す。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

参照は、状態を変える CommandHandler とは別のクラスに置く。
**QueryService**（`<Feature>QueryService`）は、[参照のインタフェース](feature-queries.md)の各メソッドを、読み取り専用のトランザクションで実装するクラスである。
自モジュールの Controller と他モジュールは、QueryService ではなく `<Feature>Queries` を使う。

QueryService は状態を変えない。
Repository の `add` と `update` を呼ばず、イベントを発行しない。

QueryService は SQL を書く場所でもない。
jOOQ で画面ごとの射影を読まず、[Repository](repository.md) が返す集約から[参照の結果](query-result.md)を作る。

## 置き場所と命名

- `com.example.demo.<feature>.application` に置く。
- 名前は `<Feature>Queries` の `Queries` を `QueryService` に替える（`OrderQueries` の実装は `OrderQueryService`）。
- 機能モジュールに一つ作る。

## 必須の記述

- `@Service` を付けた package-private の `class` にし、`final` を付けない。
- `implements <Feature>Queries` にし、public メソッドは `@Override` を付けたインタフェースのメソッドだけにする。
- public メソッドすべてに `@Transactional(readOnly = true)` を付け、クラスには付けない。
- 依存は `<Aggregate>Repository` だけにし、package-private のコンストラクタで受け取る。
- 引数の標準型を値オブジェクトに変換して Repository を呼ぶ（`new CustomerId(criteria.customerId())`）。
- 集約は、状態を `OrderStatus.name()`、金額を `Money.amount()` のような標準型の値にして、ルートの record に変換する。
- クラス、フィールド、コンストラクタに Javadoc を書く。
  `@Override` のメソッドの説明は `<Feature>Queries` の Javadoc に書く。
- `application` のパッケージの `package-info.java` は Command と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、自モジュールのルートの `<Feature>Queries`、参照の結果、検索条件、同じモジュールの `domain.model` の集約、値オブジェクト、Repository、`@Service`、`@Transactional`。
- **依存してはいけない型**：jOOQ の API と生成型（`DSLContext`）、`infrastructure.persistence` の型、Command、Result、CommandHandler、Presentation の型、`ApplicationEventPublisher`、外部システムのインタフェース、他モジュールの型。

## 最小の例と典型的な例

最小の例は、Repository の集約を一覧の1行に変換する `search` である（抜粋）。

```java
// com.example.demo.ordering.application.OrderQueryService（抜粋）
@Override
@Transactional(readOnly = true)
public List<OrderSummary> search(final OrderSearchCriteria criteria) {
  return orderRepository.findByCustomer(new CustomerId(criteria.customerId())).stream()
      .map(OrderQueryService::toSummary)
      .toList();
}
```

典型的な例は、`OrderQueries` の二つのメソッドを実装した `OrderQueryService` の全体である。

```java
package com.example.demo.ordering.application;

/** 注文の参照を、Repository で読んだ集約から作る。 */
@Service
class OrderQueryService implements OrderQueries {

  /** 注文を取り出す Repository。 */
  private final OrderRepository orderRepository;

  /** 注文を取り出す Repository を受け取る。 */
  /* package */ OrderQueryService(final OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<OrderDetails> findDetails(final String orderId) {
    return orderRepository.findById(new OrderId(UUID.fromString(orderId))).map(OrderQueryService::toDetails);
  }

  @Override
  @Transactional(readOnly = true)
  public List<OrderSummary> search(final OrderSearchCriteria criteria) {
    return orderRepository.findByCustomer(new CustomerId(criteria.customerId())).stream()
        .map(OrderQueryService::toSummary)
        .toList();
  }

  private static OrderSummary toSummary(final Order order) {
    return new OrderSummary(
        order.id().value().toString(),
        order.status().name(),
        order.total().amount(),
        order.placedAt(),
        order.lockNo());
  }

  private static OrderDetails toDetails(final Order order) {
    final List<OrderDetails.Line> lines =
        order.lines().stream()
            .map(
                line ->
                    new OrderDetails.Line(
                        line.lineNumber(),
                        line.productCode().value(),
                        line.quantity().value(),
                        line.unitPrice().amount()))
            .toList();
    return new OrderDetails(
        order.id().value().toString(),
        order.customerId().value(),
        order.status().name(),
        lines,
        order.subtotal().amount(),
        order.discount().amount(),
        order.total().amount(),
        order.placedAt(),
        order.lockNo());
  }
}
```

## 対応するテスト

`@ApplicationModuleTest` でモジュールを起動し、`<Feature>Queries` を通して結果を確かめる。
準備に Repository で保存した集約はコミットされるため、`CleanGeneratedTablesExtension` で各テスト後に後始末する。
書き方は[バックエンドのDBテスト](../testing-database.md)の「コミットするテスト」に従う。

```java
/** 注文の参照を検証する。 */
@ApplicationModuleTest
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class OrderQueryServiceTest {

  /** テスト対象の参照のインタフェース。 */
  @Autowired private OrderQueries orderQueries;

  /** 参照する注文を準備する Repository。 */
  @Autowired private OrderRepository orderRepository;

  @Test
  @DisplayName("保存した注文の詳細を、状態の名前で返す")
  void findsDetailsOfSavedOrder() {
    final Order order =
        Order.place(
            orderRepository.nextId(),
            new CustomerId("C-1"),
            List.of(
                new OrderLine(
                    1, new ProductCode("P-1"), new Quantity(2), new Money(new BigDecimal("500")))),
            Instant.parse("2026-10-03T00:00:00Z"));
    orderRepository.add(order);

    final OrderDetails details =
        orderQueries
            .findDetails(order.id().value().toString())
            .orElseThrow(
                () -> new AssertionError("order が見つからない: orderId=" + order.id().value()));
    assertThat(details.status()).as("orderId=%s の状態", order.id().value()).isEqualTo("PLACED");
  }
}
```

## アンチパターン

- QueryService で jOOQ の `DSLContext` を使い、画面ごとの射影を SQL で読む。
  状態の解釈が集約と SQL の二か所に分かれる。
- 読み取り専用の別のインタフェースを `application` に作り、QueryService から呼ぶ。
- QueryService を public にし、Controller や他モジュールから直接使う。
  `<Feature>Queries` を通らない入口ができる。
- `@Transactional(readOnly = true)` をクラスに付ける、または付け忘れる。
- 集約や値オブジェクトを返す。
- QueryService で集約の状態を変えて保存する。

## 作成時のチェックリスト

- [ ] `application` に置き、同じモジュールのルートの `<Feature>Queries` を実装し、public メソッドすべてに `@Transactional(readOnly = true)` を付ける。［ArchUnit で検査：ClassRoleArchTest.queryServicesImplementModuleQueries］
- [ ] `application` の `@Service` の名前を `QueryService` で終える。［ArchUnit で検査：ClassRoleArchTest.applicationServicesHaveRoleNames］
- [ ] クラスに `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalIsNotDeclaredAtClassLevel］
- [ ] `@Transactional` を付けたメソッドを public にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods］
- [ ] jOOQ の API と生成型を使わない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.databaseTechnologyApisAreOnlyUsedByPersistenceAdapters］
- [ ] Presentation と Infrastructure に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] package-private の class にし、依存は Repository だけにする。［自分で点検］
- [ ] 集約を標準型の値でルートの record に変換し、状態を変えない。［自分で点検］
- [ ] クラス、フィールド、コンストラクタに Javadoc を書く。［自分で点検］
- [ ] `@ApplicationModuleTest` で `<Feature>Queries` を通したテストを書く。［自分で点検］
- [ ] `application` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
