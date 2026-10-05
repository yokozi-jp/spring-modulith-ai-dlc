---
type: Convention
title: クラスの役割：Domain Service
description: domain.service に置く Domain Service を作る三つの場合、置き場所と命名、必須の記述（@Service）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。業務規則の置き場所が集約や値オブジェクトに決まらないとき、Domain Service を作るときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Domain Service

Domain Service は、集約にも値オブジェクトにも置けない業務規則を置くクラスであり、`domain.service` に置いて `@Service` を付ける。
作るのは三つの場合だけであり、まず Entity か値オブジェクトに置けないかを確かめる。
多くの機能モジュールには Domain Service がない。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

業務規則は、まず[値オブジェクト](value-object.md)か [Entity](entity.md)（[集約](aggregate.md)を含む）に置く。
そこに置くと、一つの集約の外にある情報が要る規則が残る。
**Domain Service** は、そのような業務規則を、状態を持たないクラスとして表す。

Domain Service を作るのは、次の三つの場合だけである。

1. **複数の集約にまたがる規則**：顧客の会員ランクと注文の小計から割引額を決める `DiscountPolicy`。
2. **どの集約にも自然に属さない計算**：複数の値オブジェクトだけから決まる計算。
3. **Repository を使って確かめる規則**：「未出荷の注文は3件まで」を確かめる `OrderLimitPolicy`。

Domain Service は、ユースケースの進行役ではない。
トランザクション、イベントの発行、外部システムの呼び出し、他モジュールの参照は `<UseCase>CommandHandler` が担う。
CommandHandler は Domain Service に、必要な値を Domain の型で渡す。

## 置き場所と命名

- `com.example.demo.<feature>.domain.service` に置く。
- 名前は業務規則を表す名詞にする（`DiscountPolicy`、`OrderLimitPolicy`）。
  `OrderDomainService`、`OrderManager`、`OrderHelper` のような、規則を表さない名前にしない。
- メソッドは規則の内容を表す名前にする。
  値を決めるメソッドは結果の名前（`discountFor`）、確かめるメソッドは `ensure` で始める（`ensureCanPlace`）。

## 必須の記述

- `public class` にし、`final` を付けない。
- Spring の `@Service` を付ける。
  `@Service` は Domain に許す唯一の Spring の型であり、コンポーネントスキャンで Bean になる。
  `@Configuration` の `@Bean` で登録しない。
- 依存は public のコンストラクタで受け取り、`private final` フィールドに持つ。
  フィールドは依存と定数だけにし、状態を持たない。
- Repository は読み取り（`count*`、`find*`）にだけ使い、`update` と `delete` を呼ばない。
- 規則を満たさないときは `IllegalStateException` を投げ、メッセージは英語で、対象の識別子を含める。
- `@Transactional` と `@Slf4j` を付けず、`ApplicationEventPublisher` を使わない。
- クラス、フィールド、コンストラクタ、public メソッドに Javadoc を書く。
- `domain.service` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、`lombok..`、同じモジュールの `domain.model` と `domain.service` の型（Repository を含む）、`org.springframework.stereotype.Service`。
- **依存してはいけない型**：`@Service` 以外の Spring の型（`@Transactional`、`ApplicationEventPublisher`）、`org.slf4j`、モジュールルートの型と他モジュールの `<Feature>Queries`、`application`、Infrastructure、外部システムのインタフェース（`PaymentGateway`）。

## 最小の例と典型的な例

最小の例は、Repository を使わない `DiscountPolicy` である。

```java
package com.example.demo.order.domain.service;

/** 会員ランクと注文の小計から割引額を決める。 */
@Service
public class DiscountPolicy {

  /** ゴールド会員の割引率。 */
  private static final BigDecimal GOLD_RATE = new BigDecimal("0.05");

  /** ゴールド会員には小計の 5 % を小数点以下を切り捨てて返し、標準会員には 0 を返す。 */
  public Money discountFor(final MembershipRank rank, final Money subtotal) {
    return switch (rank) {
      case GOLD ->
          new Money(subtotal.amount().multiply(GOLD_RATE).setScale(0, RoundingMode.DOWN));
      case STANDARD -> Money.ZERO;
    };
  }
}
```

典型的な例は、Repository で数えて規則を確かめる `OrderLimitPolicy` である。

```java
package com.example.demo.order.domain.service;

/** 「未出荷の注文は3件まで」という、複数の注文にまたがる業務規則。 */
@Service
public class OrderLimitPolicy {

  /** 顧客1人あたりの未出荷の注文の上限。 */
  private static final long MAX_UNSHIPPED_ORDERS = 3;

  /** 未出荷の注文を数える Repository。 */
  private final OrderRepository orderRepository;

  /** 未出荷の注文を数える Repository を受け取る。 */
  public OrderLimitPolicy(final OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  /** 顧客が新しい注文を出せることを確かめる。 */
  public void ensureCanPlace(final CustomerId customerId) {
    if (orderRepository.countUnshippedByCustomer(customerId) >= MAX_UNSHIPPED_ORDERS) {
      throw new IllegalStateException(
          "unshipped order limit reached: customerId=" + customerId.value());
    }
  }
}
```

`PlaceOrderCommandHandler` は、他モジュールから読んだ値を Domain の型に直して Domain Service に渡す。

```java
// com.example.demo.order.application.PlaceOrderCommandHandler（抜粋）
orderLimitPolicy.ensureCanPlace(customerId);
final CustomerMembership membership =
    customerQueries
        .findMembership(command.customerId())
        .orElseThrow(
            () ->
                new NoSuchElementException("customer not found: customerId=" + command.customerId()));
final MembershipRank rank = MembershipRank.valueOf(membership.rank());
order.applyDiscount(discountPolicy.discountFor(rank, order.subtotal()));
```

## 対応するテスト

Spring を起動しない JUnit のテストを、Domain Service と同じパッケージのテストソースに置く（`DiscountPolicyTest`、`OrderLimitPolicyTest`）。
Repository を使う Domain Service には、テストの中で `OrderRepository` を実装した小さなクラスを渡す。

```java
/** 会員ランクに応じた割引額を検証する。 */
class DiscountPolicyTest {

  @Test
  @DisplayName("ゴールド会員は小計の 5 % を小数点以下切り捨てで割り引く")
  void goldMemberGetsFivePercentRoundedDown() {
    final Money discount =
        new DiscountPolicy().discountFor(MembershipRank.GOLD, new Money(new BigDecimal("1999")));

    assertThat(discount).isEqualTo(new Money(new BigDecimal("99")));
  }
}
```

## アンチパターン

- 業務規則をすべて Domain Service に置き、集約を getter だけのデータの入れ物にする。
  [ドメインモデル貧血症](https://www.martinfowler.com/bliki/AnemicDomainModel.html)になり、集約の不変条件が守られない。
- 一つの集約だけで判定できる規則（注文の状態遷移）を Domain Service に置く。
- Domain Service で `@Transactional` を付ける、イベントを発行する、外部システムを呼ぶ、ログを出す。
  これらは `<UseCase>CommandHandler` の役割である。
- 他モジュールの `<Feature>Queries` を Domain Service から呼ぶ。
- `@Service` を付けずに `@Configuration` の `@Bean` で登録する。
- Domain Service を `application` に置き、ユースケースの進行と業務規則を一つのクラスに混ぜる。

## 作成時のチェックリスト

- [ ] 規則を値オブジェクトか Entity に置けず、三つの場合のどれかに当てはまることを確かめた。［自分で点検］
- [ ] `domain.service` に置き、`@Service` を付ける。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainServicesAreAnnotatedWithService］
- [ ] `@Service` を付けた型は `application` か `domain.service` にだけ置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.servicesResideInApplicationOrDomainService］
- [ ] 依存は Java の標準型、Domain の型、`@Service` だけにし、`@Transactional`、`ApplicationEventPublisher`、ロガー、モジュールルートの型を使わない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.domainServicesDependOnlyOnDomainAndJava］
- [ ] `application` と Infrastructure に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] 外部システムのインタフェースを呼ばない。［自分で点検］
- [ ] Repository の `update` と `delete` を呼ばない。［ArchUnit で検査：TableWriterArchTest.onlyCommandHandlersUpdateOrDeleteAggregates］
- [ ] 名前は業務規則を表す名詞にする。［自分で点検］
- [ ] `public class` にし、依存を public のコンストラクタで受け取り、状態を持たない。［自分で点検］
- [ ] 規則を満たさないときは `IllegalStateException` を投げる。［自分で点検］
- [ ] クラス、フィールド、コンストラクタ、public メソッドに Javadoc を書く。［自分で点検］
- [ ] Spring を起動しない JUnit のテストを書く。［自分で点検］
- [ ] `domain.service` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
