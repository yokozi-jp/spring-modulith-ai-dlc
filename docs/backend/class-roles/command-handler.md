---
type: Convention
title: クラスの役割：CommandHandler
description: application に置き、状態を変えるユースケースを一つ実行する CommandHandler の定義、置き場所と命名、必須の記述（handle と @Transactional）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。状態を変えるユースケースを作るとき、トランザクション境界とイベントの発行の場所を確かめるときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：CommandHandler

`<UseCase>CommandHandler` は、状態を変えるユースケース一つを実行するクラスであり、`application` に置いて `@Service` を付ける。
public メソッドは `@Transactional` を付けた `handle` 一つだけにし、Command を受け取って Result を返す。
集約と Domain Service を組み合わせて Repository で保存し、他モジュールへはイベントで伝える。
役割の決定理由は [ADR-044](../../adr/ADR-044-define-backend-class-roles-and-naming.md) に示す。

## 定義

状態を変えるユースケースには、処理の順序とトランザクション境界を一か所で決める場所が要る。
**CommandHandler**（`<UseCase>CommandHandler`）は、一つのユースケースを、[Command](command.md) の受け取りから [Result](result.md) の返却まで、一つのトランザクションで進めるクラスである。

CommandHandler は業務規則の置き場所ではない。
状態遷移の判定や金額の計算は[集約](aggregate.md)、[値オブジェクト](value-object.md)、[Domain Service](domain-service.md) に置き、CommandHandler は呼ぶ順序だけを決める。

CommandHandler は他モジュールから呼ばれない。
他モジュールは発行された[イベント](event.md)を自分の [Listener](listener.md) で受け、自分の CommandHandler を呼ぶ。

## 置き場所と命名

- `com.example.demo.<feature>.application` に置く。
- 状態を変えるユースケースごとに一つ作る（`PlaceOrderCommandHandler`、`ConfirmOrderCommandHandler`、`CancelOrderCommandHandler`）。
- 名前は業務の動詞と集約の名前に `CommandHandler` を付ける。
  動詞はユビキタス言語の語（受付の `Place`、確定の `Confirm`、取消の `Cancel`）にし、`Create`、`Update`、`Delete` のような CRUD の語にしない。
- 参照だけのユースケースは CommandHandler にせず、[QueryService](query-service.md) に置く。

## 必須の記述

- `public class` にし、`final` を付けず、`@Service` を付ける。
- public メソッドは `public <UseCase>Result handle(final <UseCase>Command command)` の一つだけにする。
  `handle` に `@Transactional` を付け、クラスには付けない。
- 依存は public のコンストラクタで受け取り、`private final` フィールドに持つ。
- `handle` の中で、Command の標準型の値を値オブジェクトに変換する（`new OrderId(command.orderId())`）。
- 集約が見つからないときは、`.orElseThrow(() -> new NoSuchElementException("order not found: orderId=" + command.orderId()))` で `NoSuchElementException` を投げる。
- 状態を変えた集約は、`handle` の中で Repository の `save` で保存する。
- イベントは、保存の後に `ApplicationEventPublisher` の `publishEvent` で発行する。
- 現在時刻は、コンストラクタで受け取った `Clock` から `Instant.now(clock)` で取る。
- 別の CommandHandler を呼ばない。
  後続の処理はイベントを発行し、受信側の Listener に任せる。
- 他モジュールの情報は相手の[参照のインタフェース](feature-queries.md)で読み、Domain の型に直して集約と Domain Service に渡す。
- 外部システムは[外部システムのインタフェース](external-system-interface.md)を通して呼ぶ。
- クラス、フィールド、コンストラクタ、`handle` に Javadoc を書く。
- `application` のパッケージの `package-info.java` は Command と共有する。

Command の形式は、Controller の `@Valid` で検証済みである。
形式の違反は、`ApiExceptionHandler` が継承する `ResponseEntityExceptionHandler` が 400 の Problem Details にするため、CommandHandler に届かない。

Domain が投げる JDK の例外は、いまは HTTP の 500 になる。
ユースケースがこの例外を 400、404、422 で返す必要があるときは、実装を止めて利用者に確認し、対応づけを新しい ADR で決める。
ステータスコードの使い分けは[HTTPステータスコードの選択](../../web-api/status-codes.md)に、API のエラー契約は [ADR-013](../../adr/ADR-013-standardize-http-api-contracts.md) に従う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型（`Clock` を含む）、`org.jspecify..`、同じ `application` の Command と Result、同じモジュールの `domain.model`（集約、値オブジェクト、Repository、外部システムのインタフェース）と `domain.service`、モジュールルートの型、他モジュールの `<Feature>Queries` とルートの record、`@Service`、`@Transactional`、`ApplicationEventPublisher`。
- **依存してはいけない型**：別の CommandHandler、Presentation の型（Request、Response）、Infrastructure の型（`JooqOrderRepository`、`PaymentGatewayClient`）、jOOQ の API と生成型、他モジュールの内部パッケージの型。

## 最小の例と典型的な例

最小の例は、注文を取り消す `CancelOrderCommandHandler` である。

```java
package com.example.demo.order.application;

/** 注文を取り消す。 */
@Service
public class CancelOrderCommandHandler {

  /** 注文を取り出して保存する Repository。 */
  private final OrderRepository orderRepository;

  /** イベントを発行する。 */
  private final ApplicationEventPublisher events;

  /** 取消時刻を取る時計。 */
  private final Clock clock;

  /** 依存を受け取る。 */
  public CancelOrderCommandHandler(
      final OrderRepository orderRepository,
      final ApplicationEventPublisher events,
      final Clock clock) {
    this.orderRepository = orderRepository;
    this.events = events;
    this.clock = clock;
  }

  /** 注文を取り消して保存し、取消のイベントを発行する。 */
  @Transactional
  public CancelOrderResult handle(final CancelOrderCommand command) {
    final Order order =
        orderRepository
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () -> new NoSuchElementException("order not found: orderId=" + command.orderId()));
    order.cancel();
    orderRepository.save(order);
    events.publishEvent(new OrderCancelled(order.id().value(), Instant.now(clock)));
    return new CancelOrderResult(order.id().value());
  }
}
```

典型的な例は、注文を受け付ける `PlaceOrderCommandHandler` の `handle` である（抜粋）。
依存は `orderRepository`、`orderLimitPolicy`、`discountPolicy`、`customerQueries`、`productQueries`、`events`、`clock` の七つである。

```java
/** 上限を確かめて注文を受け付け、会員ランクの割引を適用して保存し、受付のイベントを発行する。 */
@Transactional
public PlaceOrderResult handle(final PlaceOrderCommand command) {
  final CustomerId customerId = new CustomerId(command.customerId());
  orderLimitPolicy.ensureCanPlace(customerId);
  final Order order =
      Order.place(OrderId.newId(), customerId, toOrderLines(command.lines()), Instant.now(clock));
  final CustomerMembership membership =
      customerQueries
          .findMembership(command.customerId())
          .orElseThrow(
              () ->
                  new NoSuchElementException("customer not found: customerId=" + command.customerId()));
  final MembershipRank rank = MembershipRank.valueOf(membership.rank());
  order.applyDiscount(discountPolicy.discountFor(rank, order.subtotal()));
  orderRepository.save(order);
  events.publishEvent(
      new OrderPlaced(order.id().value(), order.customerId().value(), order.placedAt()));
  return new PlaceOrderResult(order.id().value());
}

/** 商品の価格を参照し、Command の明細を明細番号付きの Entity に変換する。 */
private List<OrderLine> toOrderLines(final List<PlaceOrderCommand.Line> commandLines) {
  final Map<String, BigDecimal> unitPrices =
      productQueries
          .findPrices(commandLines.stream().map(PlaceOrderCommand.Line::productCode).toList())
          .stream()
          .collect(Collectors.toMap(ProductPrice::productCode, ProductPrice::unitPrice));
  return IntStream.range(0, commandLines.size())
      .mapToObj(
          index -> {
            final PlaceOrderCommand.Line line = commandLines.get(index);
            final BigDecimal unitPrice = unitPrices.get(line.productCode());
            if (unitPrice == null) {
              throw new NoSuchElementException(
                  "product not found: productCode=" + line.productCode());
            }
            return new OrderLine(
                index + 1,
                new ProductCode(line.productCode()),
                new Quantity(line.quantity()),
                new Money(unitPrice));
          })
      .toList();
}
```

`ConfirmOrderCommandHandler` は、外部システムのインタフェースで代金を請求してから注文を確定する。

```java
// com.example.demo.order.application.ConfirmOrderCommandHandler（抜粋）
paymentGateway.charge(order.id(), order.total());
order.confirm();
orderRepository.save(order);
return new ConfirmOrderResult(order.id().value());
```

## 対応するテスト

`@ApplicationModuleTest` でモジュールを起動し、`Scenario` で `handle` を呼んで、発行されたイベントを確かめる。
`Scenario` は `handle` をコミットするトランザクションで呼ぶため、`CleanGeneratedTablesExtension` で各テスト後に後始末する。
他モジュールの `<Feature>Queries` を使う CommandHandler は、`@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)` で相手のモジュールも起動する。
書き方は[バックエンドのDBテスト](../testing-database.md)の「Spring Modulithのイベント」と「コミットするテスト」に従う。

```java
/** 注文の取消を検証する。 */
@ApplicationModuleTest
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class CancelOrderCommandHandlerTest {

  /** テスト対象の CommandHandler。 */
  @Autowired private CancelOrderCommandHandler cancelOrder;

  /** 取り消す注文を準備する Repository。 */
  @Autowired private OrderRepository orderRepository;

  @Test
  @DisplayName("受付の注文を取り消すと、取消のイベントを発行する")
  void cancelsPlacedOrderAndPublishesEvent(final Scenario scenario) {
    final Order order =
        Order.place(
            OrderId.newId(),
            new CustomerId("C-1"),
            List.of(
                new OrderLine(
                    1, new ProductCode("P-1"), new Quantity(1), new Money(new BigDecimal("500")))),
            Instant.parse("2026-10-03T00:00:00Z"));
    orderRepository.save(order);
    final String orderId = order.id().value();

    scenario
        .stimulate(() -> cancelOrder.handle(new CancelOrderCommand(orderId)))
        .andWaitForEventOfType(OrderCancelled.class)
        .matchingMappedValue(OrderCancelled::orderId, orderId)
        .toArrive();
  }
}
```

## アンチパターン

- 一つのクラスに `place`、`confirm`、`cancel` のメソッドを並べ、集約ごとのサービスにする。
  依存とトランザクション境界がユースケースごとに分かれず、クラスが大きくなる。
- `CreateOrder`、`UpdateOrder`、`DeleteOrder` のような CRUD の名前にする。
  受付、確定、取消のような業務の操作がコードから読めない。
- 状態遷移の判定（`if (order.status() == OrderStatus.SHIPPED)`）を CommandHandler に書く。
  集約が状態を守れず、同じ判定が CommandHandler ごとに重複する。
- 別の CommandHandler を呼び、ユースケースを入れ子にする。
- クラスに `@Transactional` を付ける、または private メソッドに付ける。
- `Instant.now()` や `LocalDateTime.now()` で現在時刻を取る。
- 他モジュールの CommandHandler を呼ぶ。
  同期の状態変更が必要に見えたら、実装を止めて利用者に確認し、ADR を起こす。

## 作成時のチェックリスト

- [ ] 状態を変えるユースケースごとに一つ作り、業務の動詞と集約の名前に `CommandHandler` を付ける。［自分で点検］
- [ ] `application` の `@Service` の名前を `CommandHandler` で終える。［ArchUnit で検査：ClassRoleArchTest.applicationServicesHaveRoleNames］
- [ ] `@Service` を付けた型は `application` か `domain.service` にだけ置く。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.servicesResideInApplicationOrDomainService］
- [ ] public メソッドは `@Transactional` を付けた `handle` 一つにし、Command を受け取って Result を返す。［ArchUnit で検査：ClassRoleArchTest.commandHandlersExposeOnlyTransactionalHandle］
- [ ] クラスに `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalIsNotDeclaredAtClassLevel］
- [ ] 別の CommandHandler に依存しない。［ArchUnit で検査：ClassRoleArchTest.commandHandlersDoNotDependOnOtherCommandHandlers］
- [ ] 他モジュールの CommandHandler と内部パッケージの型を使わない。［Spring Modulith で検査：ApplicationModuleArchitectureTest］
- [ ] Presentation と Infrastructure に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] jOOQ の API と生成型を使わない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.databaseTechnologyApisAreOnlyUsedByPersistenceAdapters］
- [ ] Command の値を `handle` の中で値オブジェクトに変換し、業務規則を集約と Domain Service に任せる。［自分で点検］
- [ ] 状態を変えた集約を `save` し、イベントを保存の後に `ApplicationEventPublisher` で発行する。［自分で点検］
- [ ] 現在時刻は `Instant.now(clock)` で取る。［自分で点検］
- [ ] Domain の例外を 400、404、422 で返す必要があるなら、実装を止めて利用者に確認した。［自分で点検］
- [ ] クラス、フィールド、コンストラクタ、`handle` に Javadoc を書く。［自分で点検］
- [ ] `@ApplicationModuleTest` と `Scenario` のテストを書く。［自分で点検］
- [ ] `application` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
