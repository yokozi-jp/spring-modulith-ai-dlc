---
type: Convention
title: クラスの役割：Response
description: presentation.web に置き、参照の結果を HTTP の応答にする record（Response）の定義、置き場所と命名、必須の記述（static の from と items）、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。参照の結果を返す API の応答を作るとき、応答の項目を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：Response

`<QueryResult>Response` は、参照の結果を HTTP の応答の JSON にする record であり、`presentation.web` に置く。
ルートの参照の結果から static の `from(...)` で作り、一覧は `items` に配列を持つ record で包む。
ルートの record を JSON としてそのまま返さない。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

[参照の結果](query-result.md)は他モジュールとの契約であり、API の応答は HTTP の利用者との契約である。
**Response**（`<QueryResult>Response`）は、`<Feature>Queries` が返す参照の結果を、API の応答の形にした record である。
[Controller](controller.md) が `from(...)` で作って返し、Jackson が JSON にする。

Response は Domain の型を持たない。
集約や値オブジェクトからは作らず、ルートの record からだけ作る。

## 置き場所と命名

- `com.example.demo.<feature>.presentation.web` に置く。
- 名前は参照の結果の名前に `Response` を付ける（`OrderDetails` から `OrderDetailsResponse`、`OrderSummary` から `OrderSummaryResponse`）。
- 一覧は、1行の Response の名前の `Response` の前に `List` を入れ、`items` の component で包む（`OrderSummaryListResponse`）。
- 繰り返す項目は、ネストした record `Line` にする（`OrderDetailsResponse.Line`）。

## 必須の記述

- `public record` にし、Jackson のアノテーションを付けない。
- component は Java の標準型と、自分にネストした record だけにする。
- 日時は `Instant` にし、`Z` 付きの RFC 3339 の文字列で出す。
- 区分値（`status`）は、参照の結果の文字列をそのまま入れ、表示名を返さない。
- 1行と詳細の Response は、参照の結果の `lockNo` を `long lockNo` に持つ。
  クライアントは、この値を更新の本文で送り返す（[更新の競合制御](../../web-api/optimistic-locking.md)）。
- 1行と詳細の Response は、`public static <QueryResult>Response from(final <QueryResult> ...)` で作る。
- 一覧の Response は `items` だけを持ち、Controller が 1行の Response の `from` で作ったリストを渡す。
- `List` の component は、コンパクトコンストラクタで `List.copyOf` に置き換える。
- record、ネストした record、`from` に Javadoc を書く。
- `presentation.web` のパッケージの `package-info.java` は Controller と共有する。

応答の形は[レスポンスボディの形式](../../web-api/response-body.md)に、日時の形式は[日時とタイムゾーンの規約](../../datetime/timezone-conventions.md)に従う。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、自モジュールのルートの参照の結果、同じ `presentation.web` の Response、自分にネストした record。
- **依存してはいけない型**：Domain の型（`Order`、`Money`、`OrderStatus`）、`application` の Command と Result、Infrastructure の型、jOOQ の生成型、Jackson のアノテーション、他モジュールの型。

## 最小の例と典型的な例

最小の例は、一覧の1行の `OrderSummaryResponse` と、それを包む `OrderSummaryListResponse` である。

```java
package com.example.demo.order.presentation.web;

import com.example.demo.order.OrderSummary;
import java.math.BigDecimal;
import java.time.Instant;

/** 注文の一覧の1行を返す API の本文。 */
public record OrderSummaryResponse(
    String orderId, String status, BigDecimal total, Instant placedAt, long lockNo) {

  /** 参照の結果から作る。 */
  public static OrderSummaryResponse from(final OrderSummary summary) {
    return new OrderSummaryResponse(
        summary.orderId(),
        summary.status(),
        summary.total(),
        summary.placedAt(),
        summary.lockNo());
  }
}
```

```java
// com.example.demo.order.presentation.web.OrderSummaryListResponse（宣言だけ）
/** 注文の一覧を items で包んで返す API の本文。 */
public record OrderSummaryListResponse(List<OrderSummaryResponse> items) {

  /** 一覧を変更できないリストとして持つ。 */
  public OrderSummaryListResponse {
    items = List.copyOf(items);
  }
}
```

典型的な例は、明細をネストした record で持つ `OrderDetailsResponse` である。

```java
// com.example.demo.order.presentation.web.OrderDetailsResponse（宣言だけ）
/** 注文の詳細を返す API の本文。 */
public record OrderDetailsResponse(
    String orderId,
    String customerId,
    String status,
    List<OrderDetailsResponse.Line> lines,
    BigDecimal subtotal,
    BigDecimal discount,
    BigDecimal total,
    Instant placedAt,
    long lockNo) {

  /** 明細を変更できないリストとして持つ。 */
  public OrderDetailsResponse {
    lines = List.copyOf(lines);
  }

  /** 参照の結果から作る。 */
  public static OrderDetailsResponse from(final OrderDetails details) {
    return new OrderDetailsResponse(
        details.orderId(),
        details.customerId(),
        details.status(),
        details.lines().stream().map(Line::from).toList(),
        details.subtotal(),
        details.discount(),
        details.total(),
        details.placedAt(),
        details.lockNo());
  }

  /** 注文の明細の1行。 */
  public record Line(int lineNumber, String productCode, int quantity, BigDecimal unitPrice) {

    /** 参照の結果の明細から作る。 */
    public static Line from(final OrderDetails.Line line) {
      return new Line(line.lineNumber(), line.productCode(), line.quantity(), line.unitPrice());
    }
  }
}
```

## 対応するテスト

`@JsonTest` で、Response が規約どおりの JSON になることを確かめる。
テストは Response と同じ `presentation.web` パッケージのテストソースに置く。

```java
/** 注文の一覧の応答の JSON を検証する。 */
@JsonTest
class OrderSummaryListResponseTest {

  /** application.yaml の spring.jackson 設定が適用された JsonMapper。 */
  @Autowired private JsonMapper jsonMapper;

  @Test
  @DisplayName("一覧を items に入れ、受付時刻を Z 付きの文字列にし、ロック番号を返す")
  void writesItemsWithUtcInstant() {
    final OrderSummaryListResponse response =
        new OrderSummaryListResponse(
            List.of(
                new OrderSummaryResponse(
                    "O-1",
                    "PLACED",
                    new BigDecimal("1900"),
                    Instant.parse("2026-10-03T00:00:00Z"),
                    1L)));

    assertThat(jsonMapper.writeValueAsString(response))
        .as("orderId=O-1 の一覧の JSON")
        .startsWith("{\"items\":[")
        .contains("\"placedAt\":\"2026-10-03T00:00:00Z\"")
        .contains("\"lockNo\":1");
  }
}
```

## アンチパターン

- ルートの record（`OrderDetails`）や `List<OrderSummary>` を Controller からそのまま返す。
  他モジュールとの契約と API の契約が一つになり、片方の変更がもう片方に及ぶ。
- 一覧を JSON の配列のまま返す。
- 集約や値オブジェクトを Response に持たせる、または集約から作る。
  Domain の型が Application の外へ出て、HTTP API の形と Domain を独立に変えられなくなる。
- 変換を Controller の private メソッドや Mapper のクラスに書く。
- 区分値の表示名を返す。
- 日時を整形済みの `String` や `LocalDateTime` で返す。

## 作成時のチェックリスト

- [ ] `presentation.web` の record にする。［ArchUnit で検査：ClassRoleArchTest.requestsAndResponsesArePresentationWebRecords］
- [ ] Domain の型に依存しない。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.presentationDoesNotDependOnDomain］
- [ ] 名前を参照の結果の名前に `Response` を付けた形にし、一覧は `items` を持つ `<1行の名前>ListResponse` で包む。［自分で点検］
- [ ] 1行と詳細は static の `from` でルートの record から作る。［自分で点検］
- [ ] 日時は `Instant`、区分値は参照の結果の文字列のままにする。［自分で点検］
- [ ] 1行と詳細の Response に `long lockNo` を持つ。［自分で点検］
- [ ] `List` の component をコンパクトコンストラクタで `List.copyOf` に置き換える。［自分で点検］
- [ ] Jackson のアノテーションを付けない。［自分で点検］
- [ ] record、ネストした record、`from` に Javadoc を書く。［自分で点検］
- [ ] `@JsonTest` で JSON の形を確かめる。［自分で点検］
- [ ] `presentation.web` のパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
