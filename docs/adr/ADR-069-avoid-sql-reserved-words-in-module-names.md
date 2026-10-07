---
type: ADR
title: 'ADR-069: モジュール名とスキーマ名に SQL の予約語を使わない'
description: Spring Modulith の機能モジュール名と、それに一致させる PostgreSQL のスキーマ名に、PostgreSQL の予約語を使わない決定。注文のモジュールは order ではなく ordering にする。
tags: [adr, backend, database, naming, spring-modulith]
---

# ADR-069: モジュール名とスキーマ名に SQL の予約語を使わない

## Status

Proposed

## Date

2026-10-07

## Context

機能モジュールのテーブルは、モジュール名と同じ名前のスキーマに置く（[ADR-011](ADR-011-use-module-owned-database-schemas.md)）。
[PostgreSQLの命名規約](../database/postgresql-naming.md)は、テーブル名とカラム名に SQL のキーワードを使わないと定めていたが、スキーマ名とモジュール名の扱いを定めていなかった。

確認用のサンプル機能（[issue #122](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/122)）では、注文のモジュールを `order` にし、同じ名前のスキーマを作った。
`ORDER` は PostgreSQL の予約語であるため、次のことが起きた。

- Liquibase の changeset に、予約語を引用符で囲ませる `objectQuotingStrategy: QUOTE_ONLY_RESERVED_WORDS` を付ける必要があった。
- 手書きの SQL では、`"order".t_order` のようにスキーマ名を毎回引用符で囲む必要があった。
  付け忘れは構文の誤りになるが、その SQL を実行するまで分からない。
- PostgreSQL は引用符のない識別子を小文字に変換し、引用符のある識別子の大文字と小文字を区別する。
  引用符の有無で同じ名前の扱いが変わり、誤りの元になる。

モジュール名はパッケージ名、スキーマ名、`*_pgm_cd` の値（`order.PlaceOrder`）に現れ、データができた後は変えにくい。

## Decision

機能モジュール名と、それに一致させるスキーマ名には、PostgreSQL の予約語を使わない。

- 予約語かどうかは、PostgreSQL の公式文書の付録「SQL Key Words」の表で、PostgreSQL の列が reserved の語として確かめる。
- 注文のモジュールは `ordering` にする。
  `ORDERING` は同じ表で non-reserved であり、引用符なしで使える。
- テーブル名とカラム名は、今までどおり予約語かどうかに関係なくキーワードを使わない。
- docs の例のモジュール名、パッケージ名、スキーマ名、`*_pgm_cd` の値も `ordering` にする。
  クラス名（`Order`、`OrderId`）と HTTP API のパス（`/api/orders`）は変えない。

## Consequences

### Positive

- 手書きの SQL、changeset、jOOQ の生成物で、スキーマ名に引用符が要らない。
- 引用符の付け忘れで、実行するまで分からない構文の誤りが起きない。

### Negative

- モジュール名に業務の自然な名詞を使えない場合がある（`order` ではなく `ordering`）。
  パッケージ名とクラス名（`com.example.demo.ordering.Order`）で語が揃わない。

### Neutral

- 予約語は PostgreSQL の版で増えることがある。
  版を上げるときに、既存のモジュール名が予約語になっていないかを付録の表で確かめる。

## Alternatives Considered

### 選択肢1: モジュール名との一致を優先し、引用符で囲む

- **Description**：スキーマ名をモジュール名のまま `order` にし、changeset に `objectQuotingStrategy: QUOTE_ONLY_RESERVED_WORDS` を付け、任意の SQL では `"order"` と引用符で囲む（#122 のブランチの実装）。
- **Pros**：モジュール名に業務の自然な名詞を使える。jOOQ は既定で識別子を引用符付きで出すため、jOOQ の SQL では問題にならない。
- **Cons**：手書きの SQL と運用の SQL で毎回引用符が要り、付け忘れは実行するまで分からない。

### 選択肢2: スキーマ名だけをモジュール名と変える

- **Description**：モジュール名は `order` のままにし、スキーマ名だけを `ordering` のように変える。
- **Pros**：パッケージ名を変えずに済む。
- **Cons**：ADR-011 のモジュール名とスキーマ名の一致が崩れ、どのモジュールがどのスキーマを持つかを名前から読めなくなる。

## References

- [ADR-011: モジュール所有のデータベーススキーマを使う](ADR-011-use-module-owned-database-schemas.md)
- [PostgreSQLの命名規約](../database/postgresql-naming.md)
- [PostgreSQL: SQL Key Words](https://www.postgresql.org/docs/current/sql-keywords-appendix.html)
