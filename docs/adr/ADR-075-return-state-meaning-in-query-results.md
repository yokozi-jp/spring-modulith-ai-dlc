---
type: ADR
title: 'ADR-075: 他モジュールが分岐に使う状態の意味を、状態を持つモジュールが参照の結果の boolean で返す'
description: 参照の結果に状態の名前と並べて状態の意味を boolean の項目で持たせ、意味を Domain の enum の定数ごとに決め、他モジュールに状態の名前を読ませないことを ArchUnit で検査する決定。
tags: [adr, backend, architecture, ddd]
---

# ADR-075: 他モジュールが分岐に使う状態の意味を、状態を持つモジュールが参照の結果の boolean で返す

## Status

Proposed

## Date

2026-10-11

## Context

[ADR-050](ADR-050-define-backend-class-roles-and-naming.md) は、モジュールルートの参照の結果に集約の状態を Domain の enum ではなく `name()` の文字列で持たせると決めた。
しかし、他モジュールがその状態で分岐する方法は決めていなかった。

そのため、読む側は状態の名前を文字列で比べていた。
注文（`ordering`）は `"ON_SALE".equals(summary.salesStatus())` で商品が販売中かを、決済（`payment`）は `"CONFIRMED".equals(details.status())` で注文が確定したかを判断していた。
この比較は、持ち主が enum の定数の名前を変えてもコンパイルが通り、分岐だけが黙って壊れる。
定数を足したときも同じである。
たとえば注文に確定の後の状態を足すと、決済はその注文を確定していないと判断し、請求を飛ばして正常に終える。
業務上の正常として扱うため、イベント出版は COMPLETED になり、ERROR のログにもデッドレターにも残らない。

状態の意味（確定か、販売中か）を決めるのは、状態を持つモジュールである。
意味を読む側に置くと、持ち主が定数を変えるときに、読む側のどこを直すべきかが持ち主から見えない。

## Decision

- 参照の結果は、状態の名前の文字列（`status`、`salesStatus`）と並べて、他モジュールが分岐に使う状態の意味を boolean の項目（`OrderDetails.confirmed`、`ProductSummary.onSale`）で持つ。
  状態の名前の文字列は、画面の表示、API の応答、ログに使うために残す。
- 意味は Domain の enum の各定数にコンストラクタの引数で持たせ、`isConfirmed()`、`isOnSale()` で返す。
  QueryService は、参照の結果を作るときにこのメソッドの値を写し、定数と比べて意味を作らない。
  定数を足すときに引数を書かないとコンパイルが失敗するため、意味の決め忘れを防げる。
- 状態を持つモジュールの外のコードは、ルートの record の `status()` と、名前が `Status` で終わるメソッドを呼ばず、メソッド参照もしない。
  `ClassRoleArchTest.queryResultStatusIsNotReadByOtherModules` で検査する。
- 決済の `ChargeOrderCommandHandler` は、請求しなかった注文の状態の名前をログに出すため、この規則の例外にする。
  例外はクラス単位で、`ClassRoleArchTest` の完全修飾型名で指定する。
  `OrderDetails` に表示用の文字列を足して例外をなくす案より、項目を増やさないこの案のほうが小さい。

ADR-050 の「集約の状態は `name()` の文字列で持つ」は変えず、この ADR で補う。

## Consequences

### Positive

- 持ち主が定数の名前を変えても、他モジュールの分岐は壊れない。
- 定数を足すときに、他モジュールに公開する意味をその場で決めることになる。
- 状態の名前の比較が戻ると、`task test` の ArchUnit で失敗する。

### Negative

- 他モジュールが分岐に使う意味が増えるたびに、enum の引数、参照の結果の項目、QueryService の変換を足す。
- 参照の結果に、状態の名前と意味という同じ状態から導いた項目が並ぶ。
- 例外にした `ChargeOrderCommandHandler` に状態の名前による分岐を足しても、ArchUnit は検出しない。
  この形はレビューで見る。

### Neutral

- 状態を持つモジュールの中（Controller の Response への変換など）は、これまでどおり状態の名前を読む。
- ArchUnit の規則は、メソッドの名前（`status` と `*Status`）で状態の名前を見分ける。
  ほかの名前で状態の名前を持つ項目は、規則の対象にならない。

## Alternatives Considered

### Alternative 1: 状態の名前の比較のまま、規約の文章で注意する

- Description：読む側が比べる状態の名前を規約に書き、作業者が自分で点検する。
- Pros：参照の結果の項目と検査を足さずに済む。
- Cons：持ち主の定数の変更で分岐が壊れることは変わらず、点検は変更の時点の状態しか保証しない。

### Alternative 2: QueryService で定数と比べて意味の項目を作る

- Description：`order.status() == OrderStatus.CONFIRMED` のように、参照の結果を作るときに意味を求める。
- Pros：enum に引数を足さずに済む。
- Cons：enum を読んでも意味が分からず、定数を足すときに意味を決めるよう促すものがない。

### Alternative 3: モジュールルートに状態の enum を置き、読む側が定数で分岐する

- Description：Domain の enum と同じ定数を持つ enum をルートに置き、参照の結果に持たせる。
- Pros：文字列の比較がなくなり、定数の名前の変更はコンパイルで見つかる。
- Cons：ADR-050 の決定と異なり、Domain とルートの二つの enum の対応を保つ変換が要る。また、読む側の `if` の比較は、定数を足しても意味の決め忘れを検出しない。

### Alternative 4: `OrderDetails` に表示用の文字列を足し、ログの例外をなくす

- Description：ログに出すための状態の文字列を別の項目で持ち、決済はその項目をログに出す。
- Pros：ArchUnit の規則に例外が要らない。
- Cons：同じ値の項目が二つになり、読む側が表示用の項目を比べて分岐する逃げ道ができる。

## References

- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [クラスの役割：参照の結果](../backend/class-roles/query-result.md)
- [クラスの役割：値オブジェクト](../backend/class-roles/value-object.md)
- [バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)
- [Pull Request #196](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/196)
