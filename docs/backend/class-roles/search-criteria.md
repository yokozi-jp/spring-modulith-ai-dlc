---
type: Convention
title: クラスの役割：検索条件
description: 参照のインタフェースの一覧の参照に渡すモジュールルートの record（検索条件）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。一覧の参照の検索条件を作るとき、条件を足すときに読む。
tags: [convention, backend, class-role]
---

# クラスの役割：検索条件

検索条件は、[参照のインタフェース](feature-queries.md)の `search` に渡すモジュールルートの record である。
名前は機能名に `SearchCriteria` を付け、条件は Java の標準型で表す。
ページングする一覧では、[ADR-013](../../adr/ADR-013-standardize-http-api-contracts.md) の `cursor` と `limit` を条件に持たせる。
役割の決定理由は [ADR-044](../../adr/ADR-044-define-backend-class-roles-and-naming.md) に示す。

## 定義

**検索条件**（`<SearchCriteria>`）は、一覧の参照で絞り込む条件をまとめた record である。
自モジュールの Controller はクエリパラメータから、他モジュールは自分の値から検索条件を作り、`<Feature>Queries.search` に渡す。
`<Feature>QueryService` は検索条件を値オブジェクトに直し、Repository のメソッドを呼ぶ。

検索条件は SQL の条件ではない。
どの列をどう絞るかは Repository の実装が決める。

## 置き場所と命名

- モジュールルート `com.example.demo.<feature>` に置く。
- 名前は機能名に `SearchCriteria` を付ける（`OrderSearchCriteria`）。
- component の名前は、絞り込む業務の項目の名前にする（`customerId`）。
- ページングする一覧では、component に `cursor` と `limit` を加える。
  `limit` の既定値と上限は ADR-013 に、クエリパラメータの語彙は[クエリパラメータ](../../web-api/query-parameters.md)に従う。

## 必須の記述

- `public record` にし、アノテーションを付けない。
- component は Java の標準型と、同じルートの record と enum だけにする。
- 値の検査はしない。
  形式の検査は Presentation の Bean Validation が、業務の値の検査は `<Feature>QueryService` が作る値オブジェクトが担う。
- record に Javadoc を書く。
- ルートのパッケージの `package-info.java` は[参照のインタフェース](feature-queries.md)と共有する。

## 依存してよい型、してはいけない型

- **依存してよい型**：`java..` の標準型、`org.jspecify..`、同じルートパッケージの record と enum。
- **依存してはいけない型**：Domain の型（`CustomerId`）、`application` の Command と Result、`presentation.web` の Request と Response、jOOQ の生成型と `org.jooq.Condition`、Spring の型、他モジュールのルートの型。

## 最小の例と典型的な例

最小の例は、顧客で絞り込む `OrderSearchCriteria` である。

```java
package com.example.demo.order;

/** 注文の一覧の検索条件。 */
public record OrderSearchCriteria(String customerId) {}
```

典型的な例は、Controller が検索条件を作り、QueryService が値オブジェクトに直す流れである。
次の二つは抜粋である。

```java
// com.example.demo.order.presentation.web.OrderController（抜粋）
/** 顧客の注文の一覧を返す。 */
@GetMapping
/* package */ OrderSummaryListResponse search(@RequestParam final String customerId) {
  final List<OrderSummaryResponse> items =
      orderQueries.search(new OrderSearchCriteria(customerId)).stream()
          .map(OrderSummaryResponse::from)
          .toList();
  return new OrderSummaryListResponse(items);
}
```

```java
// com.example.demo.order.application.OrderQueryService（抜粋）
@Override
@Transactional(readOnly = true)
public List<OrderSummary> search(final OrderSearchCriteria criteria) {
  return orderRepository.findByCustomer(new CustomerId(criteria.customerId())).stream()
      .map(
          order ->
              new OrderSummary(
                  order.id().value(),
                  order.status().name(),
                  order.total().amount(),
                  order.placedAt()))
      .toList();
}
```

## 対応するテスト

専用のテストは作らない。
ArchUnit が形を検査し、この record を使う側のテストが中身を確かめる。

## アンチパターン

- 条件を `Map<String, Object>` や文字列の SQL 断片で渡す。
  渡せる条件が型から分からず、Repository の実装が条件を解釈することになる。
- Presentation の Request や jOOQ の `Condition` を検索条件として使う。
- 値オブジェクト（`CustomerId`）を component に持つ。
  他モジュールが Domain に依存する。
- 条件ごとに `search` のメソッドを増やす（`searchByCustomer`、`searchByStatus`）。
  条件は検索条件の component として足す。

## 作成時のチェックリスト

- [ ] モジュールルートの record にする。［ArchUnit で検査：ClassRoleArchTest.moduleRootTypesAreRecordsEnumsOrQueries］
- [ ] component は Java の標準型と同じルートの型だけにする。［ArchUnit で検査：PackageByFeatureOnionArchitectureTest.moduleApiDoesNotExposeInternalTypes］
- [ ] 名前を `<Feature>SearchCriteria` にする。［自分で点検］
- [ ] component の名前は業務の項目の名前にする。［自分で点検］
- [ ] ページングする一覧では `cursor` と `limit` を持たせる。［自分で点検］
- [ ] 検索条件の中で値を検査しない。［自分で点検］
- [ ] record に Javadoc を書く。［自分で点検］
- [ ] ルートのパッケージに `@NullMarked` の `package-info.java` がある。［Error Prone で検査：RequireExplicitNullMarking］
