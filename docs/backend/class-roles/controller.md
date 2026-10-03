---
type: Convention
title: クラスの役割：Controller
description: presentation.web に置き、集約ごとの HTTP API を受ける Controller の定義、置き場所と命名、HTTP の形（201 と Location、204、404）、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。HTTP API のエンドポイントを作るとき、ステータスコードと Location を確かめるときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Controller

`<Aggregate>Controller` は、集約ごとの HTTP API を受ける Spring MVC の Controller であり、`presentation.web` に package-private で置く。
更新は Request かパス変数から Command を作って CommandHandler を呼び、参照は `<Feature>Queries` を呼ぶ。
ステータスコード、`Location`、一覧の包み方は docs/web-api の規約に従う。
役割の決定理由は [ADR-048](../../adr/ADR-048-define-backend-class-roles-and-naming.md) に示す。

## 定義

HTTP の入力と出力をユースケースの入力と出力に変換する場所を、集約ごとに一つ決める。
**Controller**（`<Aggregate>Controller`）は、一つの集約のリソースの URL を受け、[CommandHandler](command-handler.md) か[参照のインタフェース](feature-queries.md)の呼び出しに変換するクラスである。
HTTP のメソッド、ステータスコード、ヘッダは Controller だけが扱う。

Controller は業務処理の置き場所ではない。
業務規則、トランザクション、集約の保存を Controller に書かない。

Controller は Domain の型と Repository を使わない。
Domain の型を Application の外へ出さず、HTTP API の形と Domain を独立に変えられるようにするためである。
入力は [Request](request.md) と [Command](command.md)、出力は [Result](result.md) と [Response](response.md) で受け渡す。

## 置き場所と命名

- `com.example.demo.<feature>.presentation.web` に置く。
- 名前は集約の名前に `Controller` を付ける（`OrderController`）。
- `@RequestMapping` のパスは `/api/` に集約の複数形を付ける（`/api/orders`）。
  URL の形は[Web APIの方式とURLの設計](../../web-api/api-style.md)に従う。
- ハンドラメソッドの名前は操作の動詞にする（`place`、`confirm`、`cancel`、`details`、`search`）。

注文の Controller は、次の HTTP の形にする。

- **`POST /api/orders`**：`PlaceOrderRequest` を受けて注文を受け付け、201 と作成した注文の URI の `Location` を返し、本文を返さない。
- **`POST /api/orders/{orderId}/confirm`**：パス変数から `ConfirmOrderCommand` を作って確定し、204 を返す。
- **`POST /api/orders/{orderId}/cancel`**：パス変数から `CancelOrderCommand` を作って取り消し、204 を返す。
- **`GET /api/orders/{orderId}`**：`OrderQueries.findDetails` の結果を `OrderDetailsResponse` にして 200 で返す。
  注文がなければ `ResponseStatusException(HttpStatus.NOT_FOUND)` で 404 にする。
- **`GET /api/orders?customerId=…`**：`OrderQueries.search` の結果を `OrderSummaryListResponse` の `items` に入れて 200 で返す。

確定と取消は、[Web APIの方式とURLの設計](../../web-api/api-style.md)の「カスタムメソッド」の形にする。
作成の応答は[HTTPメソッドの使い分け](../../web-api/http-methods.md)の「作成」に、ステータスコードは[HTTPステータスコードの選択](../../web-api/status-codes.md)に、一覧の形は[レスポンスボディの形式](../../web-api/response-body.md)に従う。

## 必須の記述

- `@RestController` と `@RequestMapping("/api/<resources>")` を付けた package-private の `class` にし、`final` を付けない。
- 依存は package-private のコンストラクタで受け取る。
- ハンドラメソッドは、マッピングのアノテーションの次の行に `/* package */` を書いた package-private のメソッドにする。
- 本文を受ける操作は `@Valid @RequestBody` で Request を受け、`request.toCommand()` で Command にする。
- パスだけで決まる操作は、`@PathVariable` の値で Command を作る（`new CancelOrderCommand(orderId)`）。
- 作成は `ServletUriComponentsBuilder.fromCurrentRequest()` で `Location` の URI を作り、`ResponseEntity.created(location).build()` を返す。
- 本文のない更新は `ResponseEntity.noContent().build()` を返す。
- 参照は `<Feature>Queries` を呼び、`XxxResponse.from(...)` で応答にする。
  見つからないときは `ResponseStatusException(HttpStatus.NOT_FOUND)` を投げる。
- 例外を catch しない。
  エラー応答は `ApiExceptionHandler` が Problem Details にする。
- クラス、フィールド、コンストラクタ、ハンドラメソッドに Javadoc を書く。
- `presentation.web` のパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じ `presentation.web` の Request と Response、同じモジュールの `application` の Command、Result、CommandHandler、自モジュールのルートの `<Feature>Queries`、参照の結果、検索条件、Spring MVC の型、Jakarta Bean Validation の `@Valid`。
- **依存してはいけない型**：Domain の型（集約、値オブジェクト、Repository）、`<Feature>QueryService`、Infrastructure の型、jOOQ の API と生成型、他モジュールの型、`@Transactional`。

## 最小の例と典型的な例

最小の例は、パス変数だけで注文を取り消す `OrderController` である。

```java
package com.example.demo.order.presentation.web;

/** 注文の HTTP API。 */
@RestController
@RequestMapping("/api/orders")
class OrderController {

  /** 注文を取り消す CommandHandler。 */
  private final CancelOrderCommandHandler cancelOrder;

  /** 依存を受け取る。 */
  /* package */ OrderController(final CancelOrderCommandHandler cancelOrder) {
    this.cancelOrder = cancelOrder;
  }

  /** 注文を取り消す。 */
  @PostMapping("/{orderId}/cancel")
  /* package */ ResponseEntity<Void> cancel(@PathVariable final String orderId) {
    cancelOrder.handle(new CancelOrderCommand(orderId));
    return ResponseEntity.noContent().build();
  }
}
```

典型的な例は、残りの操作を足した `OrderController` である（抜粋）。
依存は `placeOrder`、`confirmOrder`、`cancelOrder`、`orderQueries` の四つである。
`confirm` は `cancel` と同じ形なので省く。

```java
/** 注文を受け付け、作成した注文の URI を Location に入れて返す。 */
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

/** 注文の詳細を返す。 */
@GetMapping("/{orderId}")
/* package */ OrderDetailsResponse details(@PathVariable final String orderId) {
  return orderQueries
      .findDetails(orderId)
      .map(OrderDetailsResponse::from)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
}

/** 顧客の注文の一覧を返す。 */
@GetMapping
/* package */ OrderSummaryListResponse search(@RequestParam final String customerId) {
  return new OrderSummaryListResponse(
      orderQueries.search(new OrderSearchCriteria(customerId)).stream()
          .map(OrderSummaryResponse::from)
          .toList());
}
```

## 対応するテスト

`ApiContractTest` と同じく、`@SpringBootTest`、`@AutoConfigureMockMvc`、`@Import(SharedTestConfiguration.class)` で MockMvc のテストを書く。
ステータスコード、`Location`、Problem Details、Request の制約の違反を HTTP の形で確かめる。
更新の操作には `with(user(...))` と `with(csrf())` を付ける。

```java
/** 注文の HTTP API の契約を検証する。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
class OrderControllerTest {

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("明細のない注文の受付は 400 の Problem Details を返す")
  void placeWithoutLinesReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/orders")
                .with(user("test-user"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"C-1\",\"lines\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400));
  }
}
```

## アンチパターン

- Controller から Repository や集約を使う。
- 業務規則や状態の判定を Controller に書く。
- 例外を catch して、独自のエラー応答を作る。
  エラー応答の形が API ごとにばらつく。
- ルートの record（`OrderDetails`）をそのまま JSON で返す。
- 一覧を JSON の配列のまま返す。
- 作成の応答で 200 と本文を返し、`Location` を返さない。
- 確定や取消を `PUT /api/orders/{orderId}` の状態の書き換えで表す。
- 画面ごとに Controller を作り、同じ集約の API を複数のクラスに分ける。

## 作成時のチェックリスト

- [ ] `presentation.web` に置き、名前を `Controller` で終える。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.controllersResideInPresentationWeb］
- [ ] Domain の型と Repository に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain］
- [ ] Infrastructure に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.dependenciesPointInward］
- [ ] `@Transactional` を付けない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.transactionalMethodsArePublicApplicationMethods］
- [ ] 集約ごとに一つ作り、`@RequestMapping("/api/<resources>")` を付けた package-private の class にする。［自分で点検］
- [ ] 本文を受ける操作は `@Valid` の Request を `toCommand()` で Command にし、パスだけの操作はパス変数で Command を作る。［自分で点検］
- [ ] 作成は 201 と `Location`、本文のない更新は 204、見つからない参照は 404 を返す。［自分で点検］
- [ ] 参照は `<Feature>Queries` を呼んで `from(...)` で Response にし、一覧は `items` で包む。［自分で点検］
- [ ] 例外を catch せず、`ApiExceptionHandler` に任せる。［自分で点検］
- [ ] クラス、フィールド、コンストラクタ、ハンドラメソッドに Javadoc を書く。［自分で点検］
- [ ] MockMvc のテストで、ステータスコードと Problem Details を確かめる。［自分で点検］
- [ ] `presentation.web` のパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
