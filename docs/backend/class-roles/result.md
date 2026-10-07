---
type: Convention
title: クラスの役割：Result
description: CommandHandler の handle が返すユースケースの出力の record（Result）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。状態を変えるユースケースの結果を作るとき、Controller で Location を作るときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Result

`<UseCase>Result` は、CommandHandler の `handle` が返すユースケースの出力の record であり、`application` に置く。
CommandHandler ごとに必ず一つ作り、少なくとも状態を変えた集約の ID を持つ。
Controller は Result の ID から、作成したリソースの `Location` を作る。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

状態を変えるユースケースの呼び出し元は、どの集約を変えたかを知る必要がある。
**Result**（`<UseCase>Result`）は、[CommandHandler](command-handler.md) が一つのユースケースの結果を呼び出し元へ返す record である。
Controller と Listener が受け取り、Controller は作成の応答の `Location` に使う。

Result は[参照の結果](query-result.md)ではない。
画面に表示する項目は Result に詰めず、`<Feature>Queries` で読む。

Result は集約を返す手段でもない。
呼び出し元へは集約や値オブジェクトを渡さず、標準型の値だけを渡す。

## 置き場所と命名

- `com.example.demo.<feature>.application` に置く。
- 名前は [Command](command.md) と同じ `<UseCase>` に `Result` を付ける（`PlaceOrderResult`、`ConfirmOrderResult`、`CancelOrderResult`）。
- 集約の ID の component は、モジュールルートの record と同じ名前にする（`orderId`）。

## 必須の記述

- `public record` にし、アノテーションを付けない。
- component は Java の標準型だけにし、少なくとも状態を変えた集約の ID を持つ。
- 返す値がないユースケースも `handle` を `void` にせず、ID だけの Result を返す。
- ID 以外の component は、呼び出し元が処理の続きで使う値だけにする。
- record に Javadoc を書く。
- `application` のパッケージの `package-info.java` は Command と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`。
- **依存してはいけない型**：Domain の型（`Order`、`OrderId`）、モジュールルートの型、`presentation.web` の Response、jOOQ の生成型、Spring と Jackson の型、他モジュールの型。

## 最小の例と典型的な例

最小の例は、取り消した注文の ID だけを持つ `CancelOrderResult` である。

```java
package com.example.demo.ordering.application;

/** 注文を取り消すユースケースの結果。 */
public record CancelOrderResult(String orderId) {}
```

`PlaceOrderResult(String orderId)`、`ConfirmOrderResult(String orderId)`、`ChargeOrderResult(String orderId)` も同じ形である。

典型的な例は、Controller が `PlaceOrderResult` の ID で作成した注文の URI を作り、201 の `Location` に入れる場面である。

```java
// com.example.demo.ordering.presentation.web.OrderController（抜粋）
/**
 * 注文を受け付ける。
 *
 * <p>作成した注文の URI を Location に入れて返す。
 *
 * @param request 受け付ける注文の内容
 * @return 本文のない 201 の応答
 */
@Operation(operationId = "placeOrder")
@ApiResponse(
    responseCode = "201",
    headers =
        @Header(
            name = "Location",
            description = "作成した注文の URI",
            schema = @Schema(type = "string", format = "uri")))
@PostMapping
/* package */ ResponseEntity<Void> place(@Valid @RequestBody final PlaceOrderRequest request) {
  final PlaceOrderResult result = placeOrder.handle(request.toCommand());
  final URI location =
      ServletUriComponentsBuilder.fromCurrentRequest()
          .path("/{orderId}")
          .buildAndExpand(result.orderId())
          .toUri();
  return ResponseEntity.created(location).build();
}
```

作成の応答の規約は[HTTPメソッドの使い分け](../../web-api/http-methods.md)の「作成」に従う。

## 対応するテスト

専用のテストは作らない。
ArchUnit が形を検査し、この record を使う側のテストが中身を確かめる。

## アンチパターン

- `handle` を `void` にする、または集約（`Order`）を返す。
  Controller が作成したリソースの URI を作れない、または Domain に依存する。
- Result に画面の表示項目を詰め、参照の代わりにする。
  更新の結果と参照の結果が一つの record に混ざり、片方の変更がもう片方に及ぶ。
- Result を Controller からそのまま JSON で返す。
- Command と Result を一つの record で兼ねる。

## 作成時のチェックリスト

- [ ] `application` の record にする。［ArchUnit で検査：ClassRoleArchTest.commandsAndResultsAreApplicationRecords］
- [ ] CommandHandler の `handle` の戻り値の型にする。［ArchUnit で検査：ClassRoleArchTest.commandHandlersExposeOnlyTransactionalHandle］
- [ ] 名前を Command と同じ `<UseCase>` に `Result` を付けた形にする。［自分で点検］
- [ ] component は Java の標準型だけにし、状態を変えた集約の ID を持つ。［自分で点検］
- [ ] アノテーションを付けない。［自分で点検］
- [ ] record に Javadoc を書く。［自分で点検］
- [ ] `application` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
