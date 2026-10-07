---
type: ADR
title: 'ADR-065: 区分値を DB と API で enum の名前のまま持つ'
description: 区分値は Java の enum の定数名を DB の列と API の値にそのまま使い、連番のコード値を使わない。コード値は既存システムや外部システムとの連携の境界でだけ変換する決定。
tags: [adr, database, web-api, category-values]
---

# ADR-065: 区分値を DB と API で enum の名前のまま持つ

## Status

Proposed

## Date

2026-10-06

## Context

[PostgreSQLのテーブル論理設計](../database/postgresql-logical-design.md)は、[ADR-040](ADR-040-import-future-architecture-guidelines.md)で取り込んだ規約として、区分値を`01`、`02`のような連番のコード値で持つと定めていた。
[レスポンスボディの形式](../web-api/response-body.md)も、DBに格納したコード値をAPIで返すと定めていた。

一方、class-rolesの例はenumの名前を使っている。
[値オブジェクト](../backend/class-roles/value-object.md)の`OrderStatus`は`PLACED`や`CONFIRMED`の定数を持つ。
[jOOQ の Repository](../backend/class-roles/jooq-repository.md)は`convertFrom(OrderStatus::valueOf)`で列を読み、`.name()`で書く。
[Response](../backend/class-roles/response.md)は`@Schema(example = "PLACED")`を示す。
Issue #122で合意したAPIの値（`DRAFT`など）もenumの名前である。

この2つの記述が食い違っているため、実装する人がどちらに従うかを決められなかった。
区分値の表現は格納済みのデータとAPIの契約値になるため、後から変えるにはデータ移行とAPIの破壊的変更が要る。

## Decision

区分値は、Javaのenumの定数名（`DRAFT`、`CONFIRMED`）を、DBの`varchar(n)`の列とAPIの値にそのまま使う。
コード値とenumの対応表を作らない。

既存システムや外部システムとの連携でコード値が決まっている場合に限り、連携の境界でコード値とenumの名前を変換する。

## Consequences

### Positive

- 変換の層がなく、Repositoryは`valueOf`と`name()`だけで区分値を読み書きできる。
- DBとAPIの値を読めば意味が分かり、調査のたびに対応表を引かずに済む。
- OpenAPIのenumと生成型の値が、そのままフロントエンドの表示名のmessage catalogのキーになる。

### Negative

- enumの定数名を変えると格納済みの値とAPIの契約が変わる。
  名前の変更は、データ移行のchangesetとAPIの破壊的変更として扱う。
- 列の桁がコード値より長くなる。

### Neutral

- 「不明」や「適用不能」もNULLにせず、定数（たとえば`UNKNOWN`）で持つ方針は変えない。
- データ基盤へ連携するときに区分値ごとの参照テーブルを作る方針は変えない。

## Alternatives Considered

### 選択肢1: 連番のコード値をDBとAPIに持つ

- **Description**：これまでの規約どおり、`01`のようなコード値をDBに格納し、APIでも返す。
- **Pros**：列の桁が短く、定数名を変えても格納済みの値は変わらない。
- **Cons**：コード値とenumの対応表が要り、class-rolesの例と#122で合意したAPIの値に合わない。

### 選択肢2: DBはコード値、APIはenumの名前

- **Description**：DBにはコード値を格納し、APIではenumの名前を返す。
- **Pros**：APIの値が読みやすく、DBの列は短い。
- **Cons**：Repositoryでコード値とenumを変換する層が要り、DBの値を読んでも意味が分からない。

## References

- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [PostgreSQLのテーブル論理設計](../database/postgresql-logical-design.md)
- [レスポンスボディの形式](../web-api/response-body.md)
- [#122](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/122)
