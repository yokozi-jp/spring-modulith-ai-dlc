---
type: Convention
title: クラスの役割：イベント
description: 他モジュールへ状態の変化を通知するモジュールルートの record（イベント）の定義、置き場所と命名、必須の記述、依存、発行と受信の例、テスト、アンチパターン、作成時のチェックリストと、保存した出版を読めなくしない変更の規則と fixture の作り方を定める。イベントを作るとき、イベントの component や名前を変えるとき、イベントの発行と受信の場所を確かめるときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：イベント

イベントは、状態の変化を他モジュールと自モジュールの後続の処理へ通知するモジュールルートの record であり、名前は過去形にする。
発行側の `<UseCase>CommandHandler` が `handle` のトランザクションの中で `ApplicationEventPublisher` を使って発行する。
受信側のモジュールは、`application` の `<Event>Listener` で受ける。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

機能モジュールの間で状態の変化を伝える手段は、イベントだけである。
**イベント**（`<Event>`）は、すでに起きた業務上の出来事を表す record であり、他モジュールが読む公開契約である。

イベントは、自モジュールで外部システムを呼ぶ処理を、業務データの更新をコミットした後に始める手段でもある（`OrderConfirmed` を受けた `OrderConfirmedListener` が決済を始める）。
イベント出版レジストリは業務データの更新と同じトランザクションでイベントを記録するため、トランザクションアウトボックスとして働く（[メッセージングの設計](../../integration/async-messaging-design.md)の「DB 更新とメッセージ発行の整合」）。

イベントは、相手に処理を命じるメッセージではない。
受信側が何をするかは、受信側のモジュールが決める。

イベントは Domain の型ではない。
`domain.model` にイベントのクラスを置かず、モジュールルートのイベントだけを使う。

## 置き場所と命名

- 発行するモジュールのルート `com.example.demo.<feature>` に置く。
- 名前は集約の名前と過去分詞にする（`OrderPlaced`、`OrderConfirmed`、`OrderCancelled`）。
  `PlaceOrder`、`OrderPlaceEvent`、`OrderCancelRequested` のような命令形や現在形にしない。
- 受信するクラスは、受信側のモジュールの `application` に `<Event>Listener` として置く（`OrderPlacedListener`、`OrderCancelledListener`）。
  自モジュールのイベントは、発行したモジュールの `application` の `<Event>Listener` で受ける（`OrderConfirmedListener`）。

## 必須の記述

- `public record` にし、アノテーションを付けない。
- component は Java の標準型と、同じルートの record と enum だけにする。
  例外は、イベントが運ぶテナント（`shared.tenancy.TenantId`）と、イベントの型 `shared.tenancy.TenantScoped` である。
- テナントを持つイベントは `shared.tenancy.TenantScoped` を実装し、`TenantId tenantId` を持つ。
  発行する CommandHandler は `TenantContext.current()` を詰める。
  `@ApplicationModuleListener` が受けるイベントは `TenantScoped` を実装する必要がある（[ADR-105](../../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
- 集約の識別子と、出来事の時刻（`placedAt`、`cancelledAt`）を持つ。
  時刻は `Instant` にし、CommandHandler が `Instant.now(clock)` で取った値か、集約が持つ値を入れる。
- 発行は `<UseCase>CommandHandler` の `handle` の中で、`ApplicationEventPublisher.publishEvent` を呼んで行う。
  `handle` は `@Transactional` なので、イベントは業務データの更新と同じトランザクションでイベント出版レジストリに記録される。
- 受信は `<Event>Listener` の `@ApplicationModuleListener` を付けた `on` メソッドで行う。
- record に Javadoc を書く。
- ルートのパッケージの `package-info.java` は[参照のインタフェース](feature-queries.md)と共有する。
- イベントを変えるときは、`@Nullable` の component を足すことだけをする。
  足す component をプリミティブにしない。
  受信側は、足した component が `null` の出版も処理する（足す前に保存した出版は `null` で読める）。
- component の削除、名前の変更、型の変更と、record の名前とパッケージの変更はしない。
  そうしたいときは、新しい型のイベントを足す。
  古い型の出版が DB に残る間は、古い型と、それを受けるリスナーと fixture を残す。
  変更の規則の理由は [ADR-137](../../adr/ADR-137-guard-event-json-compatibility-with-versioned-fixtures.md) に示す。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じルートパッケージの record と enum、`shared.tenancy` の `TenantId` と `TenantScoped`。
- **依存してはいけない型**：Domain の型（`OrderId`、`Order`）、`application` の Command と Result、`presentation.web` の Request と Response、jOOQ の生成型、Spring と Jackson の型、他モジュールのルートの型。

## 最小の例と典型的な例

最小の例は、注文を取り消したことを表す `OrderCancelled` である。

```java
package com.example.demo.ordering;

import java.time.Instant;

/** 注文を取り消したことを他モジュールへ知らせる。 */
public record OrderCancelled(String orderId, Instant cancelledAt) {}
```

注文を確定したことを表す `OrderConfirmed(String orderId, Instant confirmedAt, TenantId tenantId)` も同じ形である。
`@ApplicationModuleListener` で受けるイベントなので、`TenantScoped` を実装し、テナントを持つ。

典型的な例は、`OrderPlaced` の定義、発行、受信の三つである。

```java
package com.example.demo.ordering;

import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.shared.tenancy.TenantScoped;
import java.time.Instant;

/** 注文を受け付けたことを他モジュールへ知らせる。 */
public record OrderPlaced(String orderId, String customerId, Instant placedAt, TenantId tenantId)
    implements TenantScoped {}
```

発行は、注文を保存した直後に `PlaceOrderCommandHandler` の `handle` の中で行う。

```java
// com.example.demo.ordering.application.PlaceOrderCommandHandler（抜粋）
orderRepository.add(order);
events.publishEvent(
    new OrderPlaced(
        order.id().value().toString(),
        order.customerId().value(),
        order.placedAt(),
        TenantContext.current()));
return new PlaceOrderResult(order.id().value().toString());
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

`@ApplicationModuleListener` で受けるイベントは、過去の各版の JSON を fixture に置き、`EventContractTest` が今の型で読めることを確かめる。
fixture は `backend/src/test/resources/events/<型の完全な名前>.v<n>.json` に置く。
`n` は 1 から始め、先頭に 0 を付けない。
fixture の中身は、その版の値を `application.yaml` の `spring.jackson` の設定で JSON にしたもので、Spring Modulith が出版に保存する形と同じである。

`EventContractTest` は、次のときに失敗し、fixture の名前と直し方を失敗のメッセージに示す。

- リスナーが受ける型に `v1` の fixture がない、版が欠ける、またはファイル名が `<型の完全な名前>.v<n>.json` の形でない。
- fixture の名前の型を読み込めない、またはその型を受けるリスナーがない（型の名前かパッケージの変更）。
- fixture を `EventSerializer` で読めない。
- 読んで書き直した JSON が、JSON の木として fixture と違う（component の削除、名前の変更、プリミティブの追加、`spring.jackson` の変更）。
- 読んだ値の `@Nullable` でない component が `null` である（必須の component の追加）。
- 最も新しい版の fixture を読んだ値に `null` の component がある（次の版の fixture の足し忘れ）。

`EventContractTest` が見つけない変更もある。

- fixture は各版で一つの値しか持たないため、component の型の変更は、その値が新しい型で読めないか、書き直すと変わるときだけ見つかる。
  `long` を `int` に変えても、fixture の値が `100` なら通る。
  DB に `5000000000` の出版が残っていれば、デプロイの後に読めない。
- component の削除と名前の変更、型の名前とパッケージの変更は、変える前の版の fixture が残る間だけ見つかる。
  fixture を一緒に書き換えるか消すとテストは通るため、pre-push の [`task test-weakening`](../../tooling/lint-and-test.md) が書き換えと削除を警告し、レビューで止める。
- 同じ名前のまま単位や意味を変える変更は、JSON の形が変わらないため見つからない。

検査が違反を見つけることは、`EventContractViolationTest` がわざと違反する型と fixture で確かめる。
fixture のテストのほかに、イベントの専用のテストは作らない。
ArchUnit が形を検査し、この record を使う側のテストが中身を確かめる。

イベントの発行は、発行する CommandHandler の `@ApplicationModuleTest` で `AssertablePublishedEvents` を使って確かめる。
受信は、受信側の Listener の `@ApplicationModuleTest` で `Scenario` を使って確かめる。
書き方は[バックエンドのDBテスト](../testing-database.md)の「Spring Modulithのイベント」に従う。

### fixture の作り方

イベントを足すか変えるときは、同じ変更で次の版の fixture を次の手順で作る。

1. `v1` は、今の型の値を Spring Modulith の `EventSerializer` で JSON に書いて作る。
   fixture がないと `EventContractTest` が失敗し、置く場所のパスを失敗のメッセージに示す。
2. 次の版は、前の版の fixture を写し、足した component に実際の値を入れて作る。
   今の型のすべての component に値を入れる。
3. component の型に範囲があるときは、境界の値を入れる。
   たとえば、`long` の component には `int` に収まらない数を、文字列には許す最も長い文字列を入れる。

書き間違いは、`EventContractTest` が読んで書き直した JSON と fixture を比べて見つける。
過去の fixture は書き換えも削除もしない。

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
- [ ] component は Java の標準型と同じルートの型だけにする（テナントの `TenantId` と `TenantScoped` を除く）。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypes］
- [ ] `@ApplicationModuleListener` で受けるイベントは `TenantScoped` を実装する。［ArchUnit で検査：ClassRoleArchTest.moduleListenerEventsAreTenantScoped］
- [ ] 名前を集約の名前と過去分詞にする。［自分で点検］
- [ ] 集約の識別子と出来事の時刻を持つ。［自分で点検］
- [ ] `<UseCase>CommandHandler` の `handle` の中で `ApplicationEventPublisher` を使って発行する。［自分で点検］
- [ ] 発行を包むクラスを作らず、`publishEvent` と `multicastEvent` を呼ぶのは `application` の CommandHandler だけにする。［ArchUnit で検査：ClassRoleArchTest.eventPublisherIsUsedOnlyByCommandHandlers］
- [ ] `domain.model` のイベントのクラスを作らない。［自分で点検］
- [ ] `infrastructure` の下には `persistence` と `client` 以外のパッケージを作らない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] 受信側は `application` の `<Event>Listener` の `on` に `@ApplicationModuleListener` を付けて受ける。［ArchUnit で検査：ClassRoleArchTest.moduleListenersAreApplicationListeners］
- [ ] 受信側の `on` は、自モジュールの CommandHandler をちょうど一つ呼ぶ。［ArchUnit で検査：ClassRoleArchTest.listenersExposeOnlyOnAndCallOneCommandHandler］
- [ ] イベントを変えるときは `@Nullable` の component を足すことだけをし、プリミティブを足さない。［テストで検査：EventContractTest］
- [ ] component の削除と名前の変更をしない。［テストで検査：EventContractTest（変える前の版の fixture が残る間だけ）］
- [ ] component の型を変えない。［自分で点検（EventContractTest は、fixture の値が新しい型で読めないか、書き直すと変わるときだけ見つける）］
- [ ] record の名前とパッケージを変えない。変えたいときは新しい型のイベントを足す。［テストで検査：EventContractTest（変える前の版の fixture が残る間だけ）］
- [ ] `@ApplicationModuleListener` で受けるイベントに、`v1` から版の欠けない fixture を `<型の完全な名前>.v<n>.json` の名前で置く。［テストで検査：EventContractTest］
- [ ] イベントを足すか変える変更で、今の型のすべての component に値を入れた次の版の fixture を足す。［テストで検査：EventContractTest］
- [ ] 範囲のある型の component には、fixture に境界の値を入れる。［自分で点検］
- [ ] 過去の fixture を書き換えも削除もしない。［自分で点検（pre-push の `task test-weakening` が警告する）］
- [ ] 受信側は、足した component が `null` の出版も処理する。［自分で点検］
- [ ] record に Javadoc を書く。［自分で点検］
- [ ] ルートのパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
