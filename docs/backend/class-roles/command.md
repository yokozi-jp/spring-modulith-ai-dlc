---
type: Convention
title: クラスの役割：Command
description: application に置くユースケースの入力の record（Command）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。状態を変えるユースケースの入力を作るとき、入力の項目を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Command

`<UseCase>Command` は、状態を変えるユースケース一つの入力を表す record であり、`application` に置く。
CommandHandler ごとに必ず一つ作り、入力が集約の ID とロック番号だけのときも作る。
項目は Java の標準型だけで表し、値オブジェクトへの変換は CommandHandler の `handle` で行う。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

状態を変えるユースケースは、Controller からも Listener からも同じ形の入力を受け取る。
**Command**（`<UseCase>Command`）は、そのユースケース一つに渡す入力を表す record である。
Controller は Request とパス変数から、Listener はイベントから Command を作り、[CommandHandler](command-handler.md) の `handle` に渡す。

Command は Domain の型ではない。
値オブジェクトや集約を持たず、入力の形式も検証しない。

Command はモジュールルートの公開契約でもない。
他モジュールは Command を使わず、[イベント](event.md)で状態の変化を受け取る。

## 置き場所と命名

- `com.example.demo.<feature>.application` に置く。
- 名前はユースケースの名前に `Command` を付ける（`PlaceOrderCommand`、`ConfirmOrderCommand`、`CancelOrderCommand`）。
  ユースケースの名前は業務の動詞と集約の名前にし、`CreateOrderCommand`、`UpdateOrderCommand`、`DeleteOrderCommand` のような CRUD の動詞にしない。
- CommandHandler と [Result](result.md) は同じ `<UseCase>` を使う（`PlaceOrderCommandHandler`、`PlaceOrderResult`）。
- 繰り返す項目は、ネストした record `Line` にする（`PlaceOrderCommand.Line`）。

## 必須の記述

- `public record` にし、アノテーションを付けない。
  Bean Validation の制約も付けない。
- component は Java の標準型（`String`、`int`、`BigDecimal`、`Instant`、`List`）と、ネストした record だけにする。
  例外として、既存の集約を変える Command は `shared.concurrency` の `ExpectedLockNo` を持てる。
- 入力が集約の ID とロック番号だけでも Command を作る（`CancelOrderCommand(String orderId, ExpectedLockNo expectedLockNo)`）。
- Controller が作り、既存の集約の状態を変える Command は、クライアントが参照の応答で受け取ったロック番号を `ExpectedLockNo expectedLockNo` に持ち、`shared.concurrency` の `VersionedCommand` を実装する（`ConfirmOrderCommand`、`CancelOrderCommand`、`ShipOrderCommand`）。
  [Request](request.md) が受け取った数値から `ExpectedLockNo` を作る。
  CommandHandler はこの値を集約の `ensureLockNo` に渡し、[PostgreSQL の排他制御](../../database/postgresql-concurrency-control.md)の「画面などから受け取った `lock_no`」との比較にする。
  新しい集約を作る Command（`PlaceOrderCommand`）と、Listener がイベントから作る Command（`ReserveStockCommand`、`ChargeOrderCommand`）は `ExpectedLockNo` を持たず、`VersionedCommand` を実装しない。
  同じ Command を Request と Listener の両方が作ると、`VersionedCommand` を外せないため、Request 用と Listener 用に分ける。
- `List` の component は、コンパクトコンストラクタで `List.copyOf` に置き換える。
  置き換えないと `task be-lint` の SpotBugs が報告する。
- record とネストした record に Javadoc を書く。
- `application` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

入力の検証は次の二か所で行い、Command では行わない。

- **形式の検証**：[Request](request.md) の Bean Validation の制約と、Controller の `@Valid` で行う。
  違反は、`ApiExceptionHandler` が継承する `ResponseEntityExceptionHandler` が 400 の Problem Details にする。
- **業務の不変条件**：CommandHandler が `handle` の中で[値オブジェクト](value-object.md)と[集約](aggregate.md)を作るときに確かめる。

業務上の失敗は[業務上の失敗の例外](business-exception.md)の型で投げる（[ADR-061](../../adr/ADR-061-map-business-exceptions-to-404-409-422.md)）。
ステータスコードの使い分けは[HTTPステータスコードの選択](../../web-api/status-codes.md)に、API のエラー契約は [ADR-013](../../adr/ADR-013-standardize-http-api-contracts.md) に従う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、自分にネストした record、`shared.concurrency` の `ExpectedLockNo` と `VersionedCommand`。
- **依存してはいけない型**：Domain の型（`OrderId`、`Quantity`）、モジュールルートの型、`presentation.web` の Request、jOOQ の生成型、Spring と Jakarta Bean Validation の型、他モジュールの型。

## 最小の例と典型的な例

最小の例は、取り消す注文の ID とロック番号だけを持つ `CancelOrderCommand` である。

```java
package com.example.demo.order.application;

import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.concurrency.VersionedCommand;

/** 注文を取り消すユースケースの入力。 */
public record CancelOrderCommand(String orderId, ExpectedLockNo expectedLockNo)
    implements VersionedCommand {}
```

注文を確定する `ConfirmOrderCommand(String orderId, ExpectedLockNo expectedLockNo)` と、出荷する `ShipOrderCommand(String orderId, ExpectedLockNo expectedLockNo)` も同じ形である。
Listener が `OrderConfirmed` から作る `ChargeOrderCommand(String orderId)` は、注文の ID だけを持つ。

典型的な例は、明細をネストした record で持つ `PlaceOrderCommand` である。

```java
package com.example.demo.order.application;

import java.util.List;

/** 注文を受け付けるユースケースの入力。 */
public record PlaceOrderCommand(String customerId, List<PlaceOrderCommand.Line> lines) {

  /** 明細を変更できないリストとして持つ。 */
  public PlaceOrderCommand {
    lines = List.copyOf(lines);
  }

  /** 注文する商品と数量。 */
  public record Line(String productCode, int quantity) {}
}
```

Controller は Request から Command を作り、パス変数の値は `toCommand` の引数で渡す。

```java
// com.example.demo.order.presentation.web.OrderController（抜粋）
final PlaceOrderResult result = placeOrder.handle(request.toCommand());
cancelOrder.handle(request.toCommand(orderId));
```

Listener は、受け取ったイベントの値から Command を作る（`new ReserveStockCommand(event.orderId())`）。

## 対応するテスト

専用のテストは作らない。
ArchUnit が形を検査し、この record を使う側のテストが中身を確かめる。

## アンチパターン

- Command に値オブジェクト（`OrderId`、`Quantity`）を持たせる。
  Controller と Listener が Domain に依存する。
- Command に `@NotBlank` などの Bean Validation の制約を付け、CommandHandler で検証する。
  形式の検証が Request と Command の二か所に分かれる。
- 入力が ID とロック番号だけのとき、Command を作らずに `handle(String orderId, long lockNo)` にする。
- Controller が作り、既存の集約の状態を変える Command に `VersionedCommand` を実装させず、画面が読んだ版を確かめずに更新する。
  他の人の変更を、気付かずに上書きする。
- Request を CommandHandler に渡し、Command を兼ねさせる。
  API の形を変えると、ユースケースの入力も変わる。
- 一つの Command に操作の種別の項目を持たせ、一つの CommandHandler で複数のユースケースを分岐させる。
- Command をモジュールルートに置き、他モジュールから CommandHandler を呼ぶ。

## 作成時のチェックリスト

- [ ] `application` の record にする。［ArchUnit で検査：ClassRoleArchTest.commandsAndResultsAreApplicationRecords］
- [ ] 名前を業務の動詞と集約の名前に `Command` を付けた形にし、CRUD の動詞を使わない。［自分で点検］
- [ ] CommandHandler ごとに一つ作り、入力が ID とロック番号だけでも作る。［自分で点検］
- [ ] Controller が作り、既存の集約の状態を変える Command は、`ExpectedLockNo` を持つ `VersionedCommand` にする。［ArchUnit で検査：TableWriterArchTest.commandsBuiltByPresentationForWritesAreVersioned］
- [ ] 同じ Command を Request と Listener の両方で作らない。［自分で点検］
- [ ] component は Java の標準型とネストした record と、`ExpectedLockNo` だけにする。［自分で点検］
- [ ] Bean Validation の制約を含め、アノテーションを付けない。［自分で点検］
- [ ] `List` の component をコンパクトコンストラクタで `List.copyOf` に置き換える。［自分で点検］
- [ ] 業務上の失敗は[業務上の失敗の例外](business-exception.md)の型で投げる。［自分で点検］
- [ ] record とネストした record に Javadoc を書く。［自分で点検］
- [ ] `application` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
