---
type: Convention
title: クラスの役割：参照の結果
description: 参照のインタフェースが返すモジュールルートの record（参照の結果）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。参照の結果の record を作るとき、項目を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：参照の結果

参照の結果は、[参照のインタフェース](feature-queries.md)が返すモジュールルートの record である。
一覧の1行と1件の詳細を別の record にし、内容を表す名前を付ける。
項目は Java の標準型と同じルートの型だけで表す。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

**参照の結果**（`<QueryResult>`）は、`<Feature>Queries` のメソッドが返す読み取り専用のデータである。
`<Feature>QueryService` が集約から作り、自モジュールの Controller と他モジュールが読む。

参照の結果は集約の写しではない。
読む側が必要とする項目だけを、標準型に直して持つ。
API の応答の形は Presentation の `<QueryResult>Response` が決めるため、参照の結果は JSON の形を決めない。

## 置き場所と命名

- モジュールルート `com.example.demo.<feature>` に置く。
- 名前は内容を表す名詞にする。
  一覧の1行は `OrderSummary`、1件の詳細は `OrderDetails` のように、機能名に内容を表す語を付ける。
  他モジュールの例は `CustomerMembership`、`ProductPrice` である。
- 結果の一部を表す型は、ネストした record にする（`OrderDetails.Line`）。

## 必須の記述

- `public record` にし、アノテーションを付けない。
- component は Java の標準型（`String`、`int`、`BigDecimal`、`Instant`、`List`）と、同じルートの record と enum だけにする。
- 集約の状態は、Domain の enum ではなく `OrderStatus.name()` の文字列で持つ。
- `List` の component は、コンパクトコンストラクタで `List.copyOf` に置き換える。
  置き換えないと、`task be-lint` の SpotBugs が `EI_EXPOSE_REP` と `EI_EXPOSE_REP2` を報告する。
- record とネストした record に Javadoc を書く。
- ルートのパッケージの `package-info.java` は[参照のインタフェース](feature-queries.md)と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じルートパッケージの record と enum。
- **依存してはいけない型**：Domain の型（`Order`、`OrderStatus`、`Money`）、`application` の Command と Result、`presentation.web` の Request と Response、jOOQ の生成型、Jackson と Spring の型、他モジュールのルートの型。

## 最小の例と典型的な例

最小の例は、一覧の1行を表す `OrderSummary` である。

```java
package com.example.demo.order;

import java.math.BigDecimal;
import java.time.Instant;

/** 注文の一覧の1行。 */
public record OrderSummary(String orderId, String status, BigDecimal total, Instant placedAt) {}
```

典型的な例は、明細をネストした record で持つ `OrderDetails` である。

```java
package com.example.demo.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 注文の詳細。 */
public record OrderDetails(
    String orderId,
    String customerId,
    String status,
    List<OrderDetails.Line> lines,
    BigDecimal subtotal,
    BigDecimal discount,
    BigDecimal total,
    Instant placedAt) {

  /** 明細を変更できないリストとして持つ。 */
  public OrderDetails {
    lines = List.copyOf(lines);
  }

  /** 注文の明細の1行。 */
  public record Line(int lineNumber, String productCode, int quantity, BigDecimal unitPrice) {}
}
```

Controller は `OrderDetailsResponse.from(details)` で応答に変換し、他モジュールは `OrderDetails` をそのまま読む。

## 対応するテスト

専用のテストは作らない。
ArchUnit が形を検査し、この record を使う側のテストが中身を確かめる。

## アンチパターン

- 集約（`Order`）や値オブジェクト（`Money`）をそのまま component に持つ。
  読む側が Domain に依存する。
- 一覧と詳細を一つの `OrderDto` や `OrderView` で兼ね、一覧では使わない項目を空にする。
- Jackson のアノテーションを付けて API の応答に兼用する。
  応答の形を変えると、他モジュールとの契約も変わる。
- 状態を Domain の `OrderStatus` で持つ。
- `List` の component をコピーせずに持つ。

## 作成時のチェックリスト

- [ ] モジュールルートの record にする。［ArchUnit で検査：ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueries］
- [ ] component は Java の標準型と同じルートの型だけにする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypes］
- [ ] 一覧の1行と1件の詳細を別の record にし、内容を表す名前を付ける。［自分で点検］
- [ ] 結果の一部はネストした record にする。［自分で点検］
- [ ] 状態は `OrderStatus.name()` の文字列で持つ。［自分で点検］
- [ ] `List` の component をコンパクトコンストラクタで `List.copyOf` に置き換える。［自分で点検］
- [ ] アノテーションを付けない。［自分で点検］
- [ ] record とネストした record に Javadoc を書く。［自分で点検］
- [ ] ルートのパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
