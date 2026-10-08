---
type: Convention
title: クラスの役割：参照のインタフェース
description: モジュールルートに置く参照のインタフェース（Feature 名に Queries を付けた型）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。機能モジュールの参照の窓口を作るとき、メソッドを足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：参照のインタフェース

`<Feature>Queries` は、機能モジュールの参照をモジュールルートで公開するインタフェースである。
すべての機能モジュールに一つ作り、自モジュールの Controller と他モジュールがこれを使う。
メソッドは読み取りだけにし、戻り値はルートの record にする。
役割の決定理由は [ADR-050](../../adr/ADR-050-define-backend-class-roles-and-naming.md) に示す。

## 定義

機能モジュールの状態を外から読む入口を一つに決めるため、ルートに参照のインタフェースを置く。
**`<Feature>Queries`** は、機能モジュールの状態を読み取る操作をまとめたインタフェースであり、実装は自モジュールの `application` にある `<Feature>QueryService` が持つ。

`<Feature>Queries` は状態を変える操作を持たない。
状態の変更は `<UseCase>CommandHandler` が担い、他モジュールへは[イベント](event.md)で伝える。

他モジュールから読まれるかどうかにかかわらず、すべての機能モジュールに作る。

## 置き場所と命名

- モジュールルート `com.example.demo.<feature>` に置く。
- 名前は機能名に `Queries` を付ける（`OrderQueries`、`CustomerQueries`、`ProductQueries`）。
- 1件または指定したキーの結果を返すメソッドは `find` で始める（`findDetails`、`findMembership`、`findPrices`）。
- [検索条件](search-criteria.md)で一覧を返すメソッドは `search` にする。
- 条件のない一覧は、空の検索条件の record を作らず、引数なしの `search()` にする。

## 必須の記述

- `public interface` にし、アノテーションを付けない。
- 1件を返すメソッドの戻り値は `Optional<参照の結果>`、一覧を返すメソッドの戻り値は `List<参照の結果>` にする。
- 戻り値の型は[参照の結果](query-result.md)の record にする。
- 引数は Java の標準型か、ルートの[検索条件](search-criteria.md)の record にする。
- インタフェースと各メソッドに Javadoc を書く。
  Javadoc の有無は `task be-lint` の PMD が検査する。
- ルートのパッケージに `@NullMarked` を宣言する `package-info.java` を置く。

```java
/** 注文モジュールの公開契約。 */
@NullMarked
package com.example.demo.ordering;

import org.jspecify.annotations.NullMarked;
```

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じルートパッケージの record と enum。
- **依存してはいけない型**：Domain の型（`Order`、`OrderId`）、`application` の Command と Result、`presentation.web` の Request と Response、jOOQ の生成型、Spring の型、他モジュールのルートの型。

## 最小の例と典型的な例

最小の例は、注文モジュールの `OrderQueries` である。

```java
package com.example.demo.ordering;

import java.util.List;
import java.util.Optional;

/** 注文の参照を、自モジュールの Controller と他モジュールへ公開する。 */
public interface OrderQueries {

  /** 注文の詳細を返す。注文がなければ空を返す。 */
  Optional<OrderDetails> findDetails(String orderId);

  /** 検索条件に合う注文の一覧を返す。 */
  List<OrderSummary> search(OrderSearchCriteria criteria);
}
```

典型的な例は、自モジュールの Controller と他モジュールの CommandHandler が `OrderQueries` を使う場面である。
次の二つは抜粋であり、フィールドとコンストラクタを省いている。

```java
// com.example.demo.ordering.presentation.web.OrderController（抜粋）
/**
 * 注文の詳細を返す。
 *
 * <p>注文がなければ 404 を返す。
 *
 * @param orderId 注文の ID
 * @return 注文の詳細
 */
@Operation(operationId = "findOrderById")
@ApiResponse(responseCode = "200")
@ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
@GetMapping("/{orderId}")
/* package */ OrderDetailsResponse details(@PathVariable final String orderId) {
  return orderQueries
      .findDetails(orderId)
      .map(OrderDetailsResponse::from)
      .orElseThrow(() -> new NotFoundException("order not found: orderId=" + orderId));
}
```

```java
// com.example.demo.inventory.application.ReserveStockCommandHandler（抜粋）
/** 注文の明細の数量で在庫を引き当てる。 */
@Transactional
public ReserveStockResult handle(final ReserveStockCommand command) {
  final OrderDetails order =
      orderQueries
          .findDetails(command.orderId())
          .orElseThrow(
              () -> new NotFoundException("order not found: orderId=" + command.orderId()));
  // order.lines() の商品コードと数量で在庫を引き当てる。
  return new ReserveStockResult(order.orderId());
}
```

## 対応するテスト

`<Feature>Queries` 自体の専用のテストは作らない。
実装の `<Feature>QueryService` を `@ApplicationModuleTest` で起動し、`<Feature>Queries` を通して結果を確かめる。
テスト種別の選び方は[バックエンドのテスト種別](../testing-strategy.md)に、コミットするテストの後始末は[バックエンドのDBテスト](../testing-database.md)に従う。

## アンチパターン

- 状態を変えるメソッド（`cancel`、`place`）を `<Feature>Queries` に足す。
  他モジュールの状態を同期で変更する入口になる。
  同期の状態変更が必要に見えたら、実装を止めて利用者に確認し、ADR を起こす。
- 戻り値に集約（`Order`）や値オブジェクト（`OrderId`）を返す。
  他モジュールと Controller が Domain に依存する。
- 他モジュールが使わないからといって `<Feature>Queries` を作らず、Controller が Repository や QueryService を直接使う。
- 画面ごとに `find` のメソッドを増やし、同じ内容の参照の結果を何種類も返す。

## 作成時のチェックリスト

- [ ] ルートに置くインタフェースは `<Feature>Queries` だけにする。［ArchUnit で検査：ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueries］
- [ ] 引数と戻り値は、Java の標準型と同じルートの record だけにする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypes］
- [ ] 機能モジュールに `<Feature>Queries` を一つ作った。［自分で点検］
- [ ] メソッドは読み取りだけにし、1件は `Optional`、一覧は `List` で返す。［自分で点検］
- [ ] 1件の参照は `find` で始め、検索条件で一覧を返すメソッドは `search`、条件のない一覧は引数なしの `search()` にする。［自分で点検］
- [ ] 実装の `<Feature>QueryService` を `application` に置き、すべての public メソッドに `@Transactional(readOnly = true)` を付ける。［ArchUnit で検査：ClassRoleArchTest.queryServicesImplementModuleQueries］
- [ ] 他モジュールは `<Feature>Queries` とルートの record だけを使い、内部パッケージを参照しない。［Spring Modulith で検査：ApplicationModuleArchitectureTest］
- [ ] インタフェースと各メソッドに Javadoc を書く。［自分で点検］
- [ ] 呼び出し側の Controller は、`Optional` が空なら try と catch を書かずに `NotFoundException` を投げる。［自分で点検］
- [ ] ルートのパッケージに `@NullMarked` の `package-info.java` を置く。［Error Prone で検査：RequireExplicitNullMarking］
