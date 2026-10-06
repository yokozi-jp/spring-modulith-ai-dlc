---
type: Convention
title: クラスの役割：Listener
description: 受信する側のモジュールの application に置き、他モジュールのイベントか、外部システムを呼ぶための自モジュールのイベントを受ける Listener の定義、置き場所と命名、必須の記述（on と @ApplicationModuleListener）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。他モジュールのイベントを受けて自モジュールの状態を変えるとき、外部システムの呼び出しをイベントで始めるときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Listener

`<Event>Listener` は、他モジュールのイベント、または外部システムを呼ぶための自モジュールのイベントを受けるクラスであり、受信する側のモジュールの `application` に package-private で置いて `@Service` を付ける。
public メソッドは `@ApplicationModuleListener` を付けた `on` 一つだけにする。
`on` はイベントから Command を作り、自モジュールの CommandHandler をちょうど一つ呼ぶ。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

他モジュールの状態の変化に応じて自モジュールの状態を変えるには、[イベント](event.md)を受ける入口が要る。
自モジュールの更新をコミットした後に外部システムを呼ぶときも、自モジュールのイベントを受ける入口が要る。
**Listener**（`<Event>Listener`）は、一つのイベントを受け、自モジュールのユースケースを一つ始めるクラスである。
`@ApplicationModuleListener` は、発行側のトランザクションがコミットした後に、新しいトランザクション（`REQUIRES_NEW`）で非同期に `on` を呼ぶ。

Listener は業務処理の置き場所ではない。
業務規則と保存は、呼び出す [CommandHandler](command-handler.md) が担う。

Listener は Infrastructure の Adapter でもない。
トランザクション境界を持つため、`application` に置く。

## 置き場所と命名

- 受信する側のモジュールの `com.example.demo.<feature>.application` に置く（在庫モジュールの `com.example.demo.inventory.application`）。
  自モジュールのイベントを受けるときは、発行したモジュールの `application` に置く（`OrderConfirmedListener` は `com.example.demo.order.application`）。
- 名前はイベントの名前に `Listener` を付ける（`OrderPlacedListener`、`OrderCancelledListener`）。
- イベント一つに Listener を一つ作る。
- 受信するメソッドの名前は `on` にする。
- 呼ぶ CommandHandler は、受信側の業務の動詞で名付ける（`OrderPlacedListener` は `ReserveStockCommandHandler`、`OrderCancelledListener` は `ReleaseStockCommandHandler`、`OrderConfirmedListener` は `ChargeOrderCommandHandler` を呼ぶ）。

## 必須の記述

- `@Service` を付けた package-private の `class` にし、`final` を付けない。
- public メソッドは `@ApplicationModuleListener public void on(final <Event> event)` の一つだけにする。
- `on` はイベントの値で [Command](command.md) を作り（`new ReserveStockCommand(event.orderId())`）、CommandHandler の `handle` を一回呼ぶ。
- `@Transactional` を付けない。
  `@ApplicationModuleListener` がトランザクションを開く。
- 依存は呼ぶ CommandHandler 一つだけにし、package-private のコンストラクタで受け取る。
  フィールド名は CommandHandler の動詞にする（`reserveStock`）。
- `on` は短い名前であり、package-private のクラスの public メソッドでもあるため、クラスに `@SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})` を理由のコメントと一緒に付ける。
- 同じイベントを二回以上受けても結果が変わらないよう、呼ぶ CommandHandler を冪等にする。
  冪等にする方法は[順序保証と冪等性](../../integration/async-ordering-and-idempotency.md)に従う。
- 受信に失敗したイベント出版はレジストリに未完了のまま残り、[非同期処理の失敗時の再試行と回復](../../integration/async-failure-recovery.md)の `IncompleteEventPublications` の手順で再投入する。
  自動の再投入は [issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108) で扱う。
- CommandHandler が投げた業務上の失敗の扱いは、[業務上の失敗の例外](business-exception.md)の表に従う。
- クラス、フィールド、コンストラクタ、`on` に Javadoc を書く。
- 受信する側の `application` のパッケージの `package-info.java` は Command と共有する。

配信の保証と再配信は[メッセージングの設計](../../integration/async-messaging-design.md)に従う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、発行側のモジュールのルートのイベント、同じ `application` の Command、Result、CommandHandler 一つ、`@Service`、`@ApplicationModuleListener`。
- **依存してはいけない型**：二つ目の CommandHandler、Domain の型（集約、Repository、Domain Service）、Presentation と Infrastructure の型、`ApplicationEventPublisher`、発行側のモジュールの内部パッケージの型。

## 最小の例と典型的な例

最小の例は、注文の受付を受けて在庫の引き当てを始める `OrderPlacedListener` である。

```java
package com.example.demo.inventory.application;

import com.example.demo.order.OrderPlaced;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/** 注文の受付を受け取り、在庫の引き当てを始める。 */
// 受信メソッドは on と命名し、トランザクション境界として public にする規約のため。
@SuppressWarnings({"PMD.ShortMethodName", "PMD.PublicMemberInNonPublicType"})
@Service
class OrderPlacedListener {

  /** 在庫を引き当てる CommandHandler。 */
  private final ReserveStockCommandHandler reserveStock;

  /** 在庫を引き当てる CommandHandler を受け取る。 */
  /* package */ OrderPlacedListener(final ReserveStockCommandHandler reserveStock) {
    this.reserveStock = reserveStock;
  }

  /** イベントから Command を作り、CommandHandler へ渡す。 */
  @ApplicationModuleListener
  public void on(final OrderPlaced event) {
    reserveStock.handle(new ReserveStockCommand(event.orderId()));
  }
}
```

典型的な例は、同じ形で注文の取消を受け、在庫を戻す `OrderCancelledListener` である（抜粋）。
二つのイベントを一つのクラスで受けず、イベントごとに Listener を分ける。

```java
// com.example.demo.inventory.application.OrderCancelledListener（抜粋）
/** イベントから Command を作り、CommandHandler へ渡す。 */
@ApplicationModuleListener
public void on(final OrderCancelled event) {
  releaseStock.handle(new ReleaseStockCommand(event.orderId()));
}
```

自モジュールのイベントを受ける例は、注文の確定を受けて決済を始める `OrderConfirmedListener` である（抜粋）。

```java
// com.example.demo.order.application.OrderConfirmedListener（抜粋）
/** イベントから Command を作り、CommandHandler へ渡す。 */
@ApplicationModuleListener
public void on(final OrderConfirmed event) {
  chargeOrder.handle(new ChargeOrderCommand(event.orderId()));
}
```

`ChargeOrderCommandHandler` は、支払い済みの注文では何もせず、注文 ID を冪等性キーにして請求するため、同じイベントを二回受けても二重に請求しない（[CommandHandler](command-handler.md)）。

呼ばれる `ReserveStockCommandHandler` が注文の明細を読む例は、[参照のインタフェース](feature-queries.md)に示す。

## 対応するテスト

`@ApplicationModuleTest` で受信する側のモジュールを起動し、`Scenario` の `publish` でイベントを発行して、CommandHandler が変えた状態を `andWaitForStateChange` で待つ。
`ReserveStockCommandHandler` は注文モジュールの `OrderQueries` を使うため、`BootstrapMode.DIRECT_DEPENDENCIES` で注文モジュールも起動する。
Listener の処理はコミットされるため、`CleanGeneratedTablesExtension` で各テスト後に後始末する。
書き方は[バックエンドのDBテスト](../testing-database.md)の「Spring Modulithのイベント」と、[バックエンドのテストコードの書き方](../testing-code-style.md)の「非同期待機」に従う。

```java
/** 注文の受付のイベントで在庫を引き当てることを検証する。 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class OrderPlacedListenerTest {

  @Test
  @DisplayName("注文の受付のイベントを受けると、その注文の在庫を引き当てる")
  void reservesStockWhenOrderIsPlaced(final Scenario scenario) {
    final String orderId = savePlacedOrder();

    scenario
        .publish(new OrderPlaced(orderId, "C-1", Instant.parse("2026-10-03T00:00:00Z")))
        .andWaitForStateChange(() -> isReserved(orderId))
        .andVerify(reserved -> assertThat(reserved).as("orderId=%s の引き当て", orderId).isTrue());
  }

  // savePlacedOrder() は注文を保存してその ID を返し、isReserved(orderId) は在庫の引き当てを読む（省略）。
}
```

## アンチパターン

- Listener に業務処理を書く、または Repository を直接使う。
- 一つの `on` から複数の CommandHandler を呼ぶ。
  一つが失敗するとイベントが再配信され、成功した処理も繰り返される。
- `@ApplicationModuleListener` の代わりに `@EventListener` を付ける。
  発行側のトランザクションの中で同期に動き、受信側の失敗が発行側の更新を巻き戻す。
- 受信するクラスを `infrastructure` に置く。
- 同じイベントを二回受けると、在庫を二重に引き当てる、または代金を二重に請求する。
- Listener から別のイベントを発行し、他モジュールへ中継する。

## 作成時のチェックリスト

- [ ] `@ApplicationModuleListener` を付けたメソッドは、`application` の `<Event>Listener` の `on` にする。［ArchUnit で検査：ClassRoleArchTest.moduleListenersAreApplicationListeners］
- [ ] public メソッドは `void on(<Event> event)` 一つにし、CommandHandler をちょうど一つ呼ぶ。［ArchUnit で検査：ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler］
- [ ] `application` の `@Service` の名前を `Listener` で終える。［ArchUnit で検査：ClassRoleArchTest.applicationServicesHaveRoleNames］
- [ ] `on` を public にする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods］
- [ ] クラスに `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalIsNotDeclaredAtClassLevel］
- [ ] 発行側のモジュールのルートのイベントだけを使い、内部パッケージの型を使わない。［Spring Modulith で検査：ApplicationModuleArchitectureTest］
- [ ] 名前をイベントの名前に `Listener` を付けた形にし、イベントごとに一つ作る。［自分で点検］
- [ ] 呼ぶ CommandHandler を冪等にする。［自分で点検］
- [ ] PMD の抑止を理由のコメントと一緒に付ける。［自分で点検］
- [ ] クラス、フィールド、コンストラクタ、`on` に Javadoc を書く。［自分で点検］
- [ ] `Scenario` の `publish` で受信を確かめるテストを書く。［自分で点検］
- [ ] 受信する側の `application` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
