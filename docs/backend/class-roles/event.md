---
type: Convention
title: クラスの役割：イベント
description: 他モジュールへ状態の変化を通知するモジュールルートの record（イベント）の定義、置き場所と命名、必須の記述、依存、発行と受信の例、テスト、アンチパターン、作成時のチェックリストを定める。イベントを作るとき、イベントの発行と受信の場所を確かめるときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：イベント

イベントは、状態の変化を他モジュールへ通知するモジュールルートの record であり、名前は過去形にする。
発行側の `<UseCase>CommandHandler` が `handle` のトランザクションの中で `ApplicationEventPublisher` を使って発行する。
受信側のモジュールは、`application` の `<Event>Listener` で受ける。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

機能モジュールの間で状態の変化を伝える手段は、イベントだけである。
**イベント**（`<Event>`）は、すでに起きた業務上の出来事を表す record であり、他モジュールが読む公開契約である。

イベントは、相手に処理を命じるメッセージではない。
受信側が何をするかは、受信側のモジュールが決める。

イベントは Domain の型ではない。
`domain.model` にイベントのクラスを置かず、モジュールルートのイベントだけを使う。

## 置き場所と命名

- 発行するモジュールのルート `com.example.demo.<feature>` に置く。
- 名前は集約の名前と過去分詞にする（`OrderPlaced`、`OrderCancelled`）。
  `PlaceOrder`、`OrderPlaceEvent`、`OrderCancelRequested` のような命令形や現在形にしない。
- 受信するクラスは、受信側のモジュールの `application` に `<Event>Listener` として置く（`OrderPlacedListener`、`OrderCancelledListener`）。

## 必須の記述

- `public record` にし、アノテーションを付けない。
- component は Java の標準型と、同じルートの record と enum だけにする。
- 集約の識別子と、出来事の時刻（`placedAt`、`cancelledAt`）を持つ。
  時刻は `Instant` にし、CommandHandler が `Instant.now(clock)` で取った値か、集約が持つ値を入れる。
- 発行は `<UseCase>CommandHandler` の `handle` の中で、`ApplicationEventPublisher.publishEvent` を呼んで行う。
  `handle` は `@Transactional` なので、イベントは業務データの更新と同じトランザクションでイベント出版レジストリに記録される。
- 受信は `<Event>Listener` の `@ApplicationModuleListener` を付けた `on` メソッドで行う。
- record に Javadoc を書く。
- ルートのパッケージの `package-info.java` は[参照のインタフェース](feature-queries.md)と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じルートパッケージの record と enum。
- **依存してはいけない型**：Domain の型（`OrderId`、`Order`）、`application` の Command と Result、`presentation.web` の Request と Response、jOOQ の生成型、Spring と Jackson の型、他モジュールのルートの型。

## 最小の例と典型的な例

最小の例は、注文を取り消したことを表す `OrderCancelled` である。

```java
package com.example.demo.order;

import java.time.Instant;

/** 注文を取り消したことを他モジュールへ知らせる。 */
public record OrderCancelled(String orderId, Instant cancelledAt) {}
```

典型的な例は、`OrderPlaced` の定義、発行、受信の三つである。

```java
package com.example.demo.order;

import java.time.Instant;

/** 注文を受け付けたことを他モジュールへ知らせる。 */
public record OrderPlaced(String orderId, String customerId, Instant placedAt) {}
```

発行は、注文を保存した直後に `PlaceOrderCommandHandler` の `handle` の中で行う。

```java
// com.example.demo.order.application.PlaceOrderCommandHandler（抜粋）
orderRepository.add(order);
events.publishEvent(
    new OrderPlaced(order.id().value(), order.customerId().value(), order.placedAt()));
return new PlaceOrderResult(order.id().value());
```

受信は、在庫モジュールの `OrderPlacedListener` が行い、自モジュールの CommandHandler を一つ呼ぶ。

```java
// com.example.demo.inventory.application.OrderPlacedListener（抜粋）
/** イベントから Command を作り、CommandHandler へ渡す。 */
@ApplicationModuleListener
public void on(final OrderPlaced event) {
  reserveStock.handle(new ReserveStockCommand(event.orderId()));
}
```

受信の配信保証と冪等性は[メッセージングの設計](../../integration/async-messaging-design.md)と[順序保証と冪等性](../../integration/async-ordering-and-idempotency.md)に従う。

## 対応するテスト

専用のテストは作らない。
ArchUnit が形を検査し、この record を使う側のテストが中身を確かめる。

イベントの発行は、発行する CommandHandler の `@ApplicationModuleTest` で `AssertablePublishedEvents` を使って確かめる。
受信は、受信側の Listener の `@ApplicationModuleTest` で `Scenario` を使って確かめる。
書き方は[バックエンドのDBテスト](../testing-database.md)の「Spring Modulithのイベント」に従う。

## アンチパターン

- `domain.model` にイベントのクラスを置き、集約にイベントを記録させる。
  他モジュールが読む契約に Domain の型が混ざる。
- 発行を包むクラスを作る。
  `ApplicationEventPublisher.publishEvent` の一行を間接にするだけである。
- 発行や受信のクラスを `infrastructure` に置く。
- Controller、Listener、Domain Service からイベントを発行する。
- 命令形の名前のイベント（`ReserveStock`）で、相手のモジュールに処理を命じる。
- イベントに集約の全項目を詰め、受信側が自分の複製を作る前提にする。
  受信側が追加の情報を要するときは、発行側の[参照のインタフェース](feature-queries.md)で読む。

## 作成時のチェックリスト

- [ ] 発行するモジュールのルートの record にする。［ArchUnit で検査：ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueries］
- [ ] component は Java の標準型と同じルートの型だけにする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypes］
- [ ] 名前を集約の名前と過去分詞にする。［自分で点検］
- [ ] 集約の識別子と出来事の時刻を持つ。［自分で点検］
- [ ] `<UseCase>CommandHandler` の `handle` の中で `ApplicationEventPublisher` を使って発行する。［自分で点検］
- [ ] 発行を包むクラスと、`domain.model` のイベントのクラスを作らない。［自分で点検］
- [ ] `infrastructure` の下には `persistence` と `client` 以外のパッケージを作らない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] 受信側は `application` の `<Event>Listener` の `on` に `@ApplicationModuleListener` を付けて受ける。［ArchUnit で検査：ClassRoleArchTest.moduleListenersAreApplicationListeners］
- [ ] 受信側の `on` は、自モジュールの CommandHandler をちょうど一つ呼ぶ。［ArchUnit で検査：ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler］
- [ ] record に Javadoc を書く。［自分で点検］
- [ ] ルートのパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
