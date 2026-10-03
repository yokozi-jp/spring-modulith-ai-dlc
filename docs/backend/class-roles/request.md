---
type: Convention
title: クラスの役割：Request
description: presentation.web に置き、リクエストボディの形と形式の検証を表す record（Request）の定義、置き場所と命名、必須の記述（Bean Validation と toCommand）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。リクエストボディを受ける操作の入力を作るとき、入力の検証を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Request

`<UseCase>Request` は、リクエストボディを受ける操作の入力を表す record であり、`presentation.web` に置く。
形式の検証を Bean Validation の制約で書き、`toCommand()` で Command に変換する。
本文を受けない操作（確定、取消）には作らない。
役割の決定理由は [ADR-044](../../adr/ADR-044-define-backend-class-roles-and-naming.md) に示す。

## 定義

HTTP のリクエストボディは、API の利用者との契約であり、ユースケースの入力とは別に変わる。
**Request**（`<UseCase>Request`）は、リクエストボディの JSON の形と、単項目の形式の検証を表す record である。
[Controller](controller.md) が `@Valid @RequestBody` で受け取り、`toCommand()` で [Command](command.md) に変換して CommandHandler に渡す。
制約は springdoc-openapi が OpenAPI のスキーマに反映する。

Request は Command ではない。
CommandHandler に Request を渡さず、Request に業務の不変条件を書かない。

## 置き場所と命名

- `com.example.demo.<feature>.presentation.web` に置く。
- 名前は Command と同じ `<UseCase>` に `Request` を付ける（`PlaceOrderRequest`）。
- 本文を受ける操作にだけ作る。
  パス変数だけで決まる操作（`POST /api/orders/{orderId}/confirm`、`POST /api/orders/{orderId}/cancel`）には Request を作らず、Controller がパス変数で Command を作る。
- 繰り返す項目は、ネストした record `Line` にする（`PlaceOrderRequest.Line`）。
- component の名前は Command と同じ camelCase にする。

## 必須の記述

- `public record` にする。
- 単項目の形式の検証を、component に Bean Validation の制約で書く（`@NotBlank`、`@NotEmpty`、`@Min(1)`）。
  ネストした record のリストは `List<@Valid Line>` にし、要素も検証する。
- 変換はインスタンスメソッド `public <UseCase>Command toCommand()` 一つにする。
- `List` の component は、コンパクトコンストラクタで `List.copyOf` に置き換える。
- record、ネストした record、`toCommand` に Javadoc を書く。
- `presentation.web` のパッケージの `package-info.java` は Controller と共有する。

形式の違反は `MethodArgumentNotValidException` になり、`ApiExceptionHandler` が継承する `ResponseEntityExceptionHandler` が 400 の Problem Details にする。
複数の項目の組み合わせ、マスタの存在、業務の状態に依存する検証は Request に書かず、[APIの入力検証の配置](../../web-api/validation.md)に従ってアプリケーションで行う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、Jakarta Bean Validation の制約、自分にネストした record、同じモジュールの `application` の Command。
- **依存してはいけない型**：Domain の型（`Quantity`、`ProductCode`）、CommandHandler、モジュールルートの型、Infrastructure の型、jOOQ の生成型、他モジュールの型。

## 最小の例と典型的な例

注文モジュールの Request は `PlaceOrderRequest` だけであり、これが最小の例と典型的な例を兼ねる。

```java
package com.example.demo.order.presentation.web;

/** 注文を受け付ける API の本文。 */
public record PlaceOrderRequest(@NotBlank String customerId, @NotEmpty List<@Valid Line> lines) {

  /** 明細を変更できないリストとして持つ。 */
  public PlaceOrderRequest {
    lines = List.copyOf(lines);
  }

  /** Command へ変換する。 */
  public PlaceOrderCommand toCommand() {
    return new PlaceOrderCommand(
        customerId,
        lines.stream()
            .map(line -> new PlaceOrderCommand.Line(line.productCode(), line.quantity()))
            .toList());
  }

  /** 注文する商品と数量。 */
  public record Line(@NotBlank String productCode, @Min(1) int quantity) {}
}
```

Controller は `@Valid @RequestBody` で受け、`toCommand()` の結果を CommandHandler に渡す。

```java
// com.example.demo.order.presentation.web.OrderController（抜粋）
@PostMapping
/* package */ ResponseEntity<Void> place(@Valid @RequestBody final PlaceOrderRequest request) {
  final PlaceOrderResult result = placeOrder.handle(request.toCommand());
  // Location を作って 201 を返す。
}
```

## 対応するテスト

Request の専用のテストは作らない。
Controller の MockMvc のテストで、制約に違反する本文が 400 の Problem Details になることを確かめる。
テストの例は [Controller](controller.md) の「対応するテスト」に示す。

## アンチパターン

- Request を CommandHandler に渡し、Command を兼ねさせる。
  API の形を変えると、ユースケースの入力も変わる。
- マスタの存在や在庫を、DB を読む Bean Validation の独自の制約で確かめる。
- 確定や取消のように本文のない操作に、空の Request を作る。
- Request に Domain の型（`Quantity`）を持たせる。
- 変換を Controller の private メソッドや Mapper のクラスに書く。
- 作成と更新で一つの Request を兼ね、片方で使わない項目を任意にする。

## 作成時のチェックリスト

- [ ] `presentation.web` の record にする。［ArchUnit で検査：ClassRoleArchTest.requestsAndResponsesArePresentationWebRecords］
- [ ] Domain の型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain］
- [ ] 本文を受ける操作にだけ作り、名前を Command と同じ `<UseCase>` に `Request` を付けた形にする。［自分で点検］
- [ ] 単項目の形式の検証を Bean Validation の制約で書き、ネストした record のリストは `List<@Valid ...>` にする。［自分で点検］
- [ ] Controller で `@Valid @RequestBody` で受ける。［自分で点検］
- [ ] `toCommand()` で Command に変換する。［自分で点検］
- [ ] `List` の component をコンパクトコンストラクタで `List.copyOf` に置き換える。［自分で点検］
- [ ] record、ネストした record、`toCommand` に Javadoc を書く。［自分で点検］
- [ ] Controller の MockMvc のテストで、違反が 400 になることを確かめる。［自分で点検］
- [ ] `presentation.web` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
