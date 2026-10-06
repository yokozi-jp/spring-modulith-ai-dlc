---
type: Convention
title: クラスの役割：Request
description: presentation.web に置き、リクエストボディの形と形式の検証を表す record（Request）の定義、置き場所と命名、必須の記述（Bean Validation、ロック番号、toCommand）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。リクエストボディを受ける操作の入力を作るとき、入力の検証を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Request

`<UseCase>Request` は、リクエストボディを受ける操作の入力を表す record であり、`presentation.web` に置く。
形式の検証を Bean Validation の制約で書き、`toCommand(...)` で Command に変換する。
既存の集約を変える操作（確定、取消）も、ロック番号を本文で受けるため Request を作る。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

HTTP のリクエストボディは、API の利用者との契約であり、ユースケースの入力とは別に変わる。
**Request**（`<UseCase>Request`）は、リクエストボディの JSON の形と、単項目の形式の検証を表す record である。
[Controller](controller.md) が `@Valid @RequestBody` で受け取り、`toCommand(...)` で [Command](command.md) に変換して CommandHandler に渡す。
制約は springdoc-openapi が OpenAPI のスキーマに反映する。

Request は Command ではない。
CommandHandler に Request を渡さず、Request に業務の不変条件を書かない。

## 置き場所と命名

- `com.example.demo.<feature>.presentation.web` に置く。
- 名前は Command と同じ `<UseCase>` に `Request` を付ける（`PlaceOrderRequest`、`ConfirmOrderRequest`、`CancelOrderRequest`）。
- 状態を変える操作ごとに作る。
  既存の集約を変える操作（`POST /api/orders/{orderId}/confirm`、`POST /api/orders/{orderId}/cancel`）は、ロック番号だけを持つ Request を受ける。
- 繰り返す項目は、ネストした record `Line` にする（`PlaceOrderRequest.Line`）。
- component の名前は Command と同じ camelCase にする。

## 必須の記述

- `public record` にする。
- 単項目の形式の検証を、component に Bean Validation の制約で書く（`@NotBlank`、`@NotEmpty`、`@Min(1)`）。
  ネストした record のリストは `List<@Valid Line>` にし、要素も検証する。
- 既存の集約を変える操作の Request は、参照の応答で返したロック番号を `@Min(1) long lockNo` に持つ。
  ロック番号は[更新の競合制御](../../web-api/optimistic-locking.md)のとおりリクエストボディで受け、`lock_no` は1から始まるため `@Min(1)` を付ける。
  HTTP の Request と OpenAPI は数値のままにする。
- `toCommand` で `new ExpectedLockNo(lockNo)` を作って Command に渡す。
  `ExpectedLockNo` を作ってよいのは `presentation.web` の Request だけであり、CommandHandler、Repository、Controller は作らない。
- 変換はインスタンスメソッド `public <UseCase>Command toCommand(...)` 一つにし、パス変数の値（`orderId`）は引数で受け取る。
  パス変数のない操作では引数を持たない（`toCommand()`）。
- `List` の component は、コンパクトコンストラクタで `List.copyOf` に置き換える。
- record、ネストした record、`toCommand` に Javadoc を書く。
  record の Javadoc には全 component の `@param` を書く（OpenAPI の property の説明になる）。
- 文字列と数値の component に `@Schema(example = "...")` を付ける。
  enum、boolean、ネストした record、`List` には付けない（[OpenAPIのアノテーションとJavadoc](../../web-api/openapi-annotations.md#example)）。
- `presentation.web` のパッケージの `package-info.java` は Controller と共有する。

形式の違反は `MethodArgumentNotValidException` になり、`ApiExceptionHandler` が処理して、type が `/problems/validation-error` の 400 になる。
`errors` 拡張に、誤りのある項目の JSON Pointer と説明が入る（[ADR-058](../../adr/ADR-058-use-path-absolute-relative-uri-for-problem-types.md)）。
複数の項目の組み合わせ、マスタの存在、業務の状態に依存する検証は Request に書かず、[APIの入力検証の配置](../../web-api/validation.md)に従ってアプリケーションで行う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、Jakarta Bean Validation の制約、`io.swagger.v3.oas.annotations.media.Schema`（`example` だけに使う）、自分にネストした record、同じモジュールの `application` の Command、`shared.concurrency` の `ExpectedLockNo`。
- **依存してはいけない型**：Domain の型（`Quantity`、`ProductCode`）、CommandHandler、モジュールルートの型、Infrastructure の型、jOOQ の生成型、他モジュールの型。

## 最小の例と典型的な例

最小の例は、取り消す注文のロック番号だけを持つ `CancelOrderRequest` である。
`ConfirmOrderRequest` も同じ形である。

```java
package com.example.demo.order.presentation.web;

import com.example.demo.shared.concurrency.ExpectedLockNo;

/**
 * 注文を取り消す API の本文。
 *
 * @param lockNo 画面が読んだ注文のロック番号
 */
public record CancelOrderRequest(@Min(1) @Schema(example = "1") long lockNo) {

  /** パス変数の注文 ID と合わせて Command へ変換する。 */
  public CancelOrderCommand toCommand(final String orderId) {
    return new CancelOrderCommand(orderId, new ExpectedLockNo(lockNo));
  }
}
```

典型的な例は、明細をネストした record で持つ `PlaceOrderRequest` である。

```java
package com.example.demo.order.presentation.web;

/**
 * 注文を受け付ける API の本文。
 *
 * @param customerId 注文する顧客の ID
 * @param lines 注文の明細
 */
public record PlaceOrderRequest(
    @NotBlank @Schema(example = "C-0001") String customerId,
    @NotEmpty List<@Valid PlaceOrderLineRequest> lines) {

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

  /**
   * 注文する商品と数量。
   *
   * @param productCode 注文する商品のコード
   * @param quantity 注文する数量
   */
  public record PlaceOrderLineRequest(
      @NotBlank @Schema(example = "P-0001") String productCode,
      @Min(1) @Schema(example = "2") int quantity) {}
}
```

Controller は `@Valid @RequestBody` で受け、`toCommand(...)` の結果を CommandHandler に渡す。

```java
// com.example.demo.order.presentation.web.OrderController（抜粋。Javadoc と 201 の @ApiResponse は Controller の例を参照）
@Operation(operationId = "placeOrder")
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
- 確定や取消で、ロック番号をクエリパラメータやヘッダで受ける。
  [更新の競合制御](../../web-api/optimistic-locking.md)は、DELETE 以外ではロック番号をリクエストボディで受け渡すと定めている。
- Request に Domain の型（`Quantity`）を持たせる。
  Domain の型が Application の外へ出て、HTTP API の形と Domain を独立に変えられなくなる。
- 変換を Controller の private メソッドや Mapper のクラスに書く。
- 作成と更新で一つの Request を兼ね、片方で使わない項目を任意にする。

## 作成時のチェックリスト

- [ ] `presentation.web` の record にする。［ArchUnit で検査：ClassRoleArchTest.requestsAndResponsesArePresentationWebRecords］
- [ ] Domain の型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain］
- [ ] 状態を変える操作ごとに作り、名前を Command と同じ `<UseCase>` に `Request` を付けた形にする。［自分で点検］
- [ ] 既存の集約を変える操作の Request に `@Min(1) long lockNo` を持たせる。［自分で点検］
- [ ] `ExpectedLockNo` は Request の `toCommand` だけで作る。［ArchUnit で検査：TableWriterArchTest.expectedLockNoIsCreatedOnlyByRequests］
- [ ] 単項目の形式の検証を Bean Validation の制約で書き、ネストした record のリストは `List<@Valid ...>` にする。［自分で点検］
- [ ] Controller で `@Valid @RequestBody` で受ける。［自分で点検］
- [ ] `toCommand(...)` で Command に変換し、パス変数の値は引数で受け取る。［自分で点検］
- [ ] `List` の component をコンパクトコンストラクタで `List.copyOf` に置き換える。［自分で点検］
- [ ] record、ネストした record、`toCommand` に Javadoc を書く。［自分で点検］
- [ ] record とネストした record の全 component に `@param` を書く。［Spectral で検査：schema-property-description］
- [ ] 文字列と数値の component に `@Schema(example = "...")` を付ける。［Spectral で検査：schema-property-example］
- [ ] Controller の MockMvc のテストで、違反が 400 になることを確かめる。［自分で点検］
- [ ] `presentation.web` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
