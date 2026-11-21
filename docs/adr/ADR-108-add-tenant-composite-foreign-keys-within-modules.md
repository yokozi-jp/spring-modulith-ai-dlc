---
type: ADR
title: 'ADR-108: 同じモジュールの表どうしの参照に、テナントを含む複合の外部キーを作る'
description: 物理の外部キーを作らない規約を改め、同じモジュールの表どうしの参照に外部キーを作る決定。RLS の表どうしは (tenant_public_id, 参照先の主キー) の複合の外部キーにして参照先に同じ組の一意索引を作り、tenant_public_id を持たない tenancy の表どうしは単独の列の外部キーにする。モジュールをまたぐ参照には外部キーを作らない。
tags: [adr, database, postgresql, constraints, tenancy, rls]
---

# ADR-108: 同じモジュールの表どうしの参照に、テナントを含む複合の外部キーを作る

## Status

Proposed

## Date

2026-11-21

## Context

[PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md)は、外部キー制約を物理の表に作らず、参照の整合性をアプリケーションで保つと定めていた。
この規則は、フューチャー株式会社の PostgreSQL 設計ガイドラインから取り込んだもので（[ADR-040](ADR-040-import-future-architecture-guidelines.md)）、理由に次の 4 つを挙げていた。

- 関連があっても参照を制約すべきでない場合があり、付ける基準が揺れる。
- 親子の片方しか使わないテストでも、両方の表にデータを用意する必要がある。
- 大量データの移行で、制約を一時的に外す手順が要る。
- `ON DELETE` と `ON UPDATE` の挙動を誤解すると、意図しない削除が起きる。

[ADR-089](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md) は、スキーマをまたぐ外部キーを作らないと決め、同じモジュールの中の外部キーの扱いを [issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369) の別の判断に残した。

[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md) は、業務の表を RLS でテナントごとに分ける。
PostgreSQL では、外部キーの検査は RLS を受けない。
外部キーがないか、参照先の主キーだけの外部キーでは、あるテナントの注文明細が別のテナントの注文を指しても、DB は止めない。
注文の主キーはテナントをまたいで一意なので、主キーの一致だけではテナントの一致を確かめられないためである。
参照元と参照先のテナントの一致を DB で止めるには、テナントを含む複合の外部キーが要る。

この点は [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363) の grilling の Q6 で決めた。
決めた時点の該当は `ordering.t_order_line.order_id` だけだった。

RLS の外に置く `tenancy` の表にも、同じモジュールの中の参照がある。
[PR #450](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/450) のレビューで、Amazon Q は `tenancy.m_user_identity.user_id` に `tenancy.m_user.user_id` への外部キーがなく、参照先のない ID 連携の行を DB が止めないと指摘した。
この指摘は、規約を改める [issue #377](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/377) で扱うことにした。

## Decision

物理の外部キーを作らない規約を改め、同じモジュールの表どうしの参照に外部キーを作る。

- `tenant_public_id` を持つ表（RLS の表）どうしの参照は、`(tenant_public_id, 参照先の主キー)` の複合の外部キーにする。参照先には、同じ組の一意索引を作る。今の該当は、`ordering.t_order_line` から `ordering.t_order` への参照である。
- `tenant_public_id` を持たない `tenancy` の表どうしの参照は、単独の列の外部キーにする（`tenancy.m_user_identity.user_id` から `tenancy.m_user.user_id` など）。
- モジュールをまたぐ参照には、外部キーを作らない。別のモジュールの行は `<対象>_public_id` の uuid の列で指す（ADR-089 のまま）。

ADR-089 の Neutral に残していた「同じモジュールの中の外部キーの扱い」は、この ADR が決める。

規則の検査は、カタログのテストと changeset の lint で行う。
検査の範囲は[PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md#外部キー)の「外部キー」に示す。

## Consequences

### Positive

- あるテナントの行が、同じモジュールの別のテナントの行を指すと、DB が INSERT と UPDATE を拒否する。アプリのコードの誤りが、テナントをまたぐ参照として残らない。
- 同じモジュールの中で、参照先のない行（注文のない注文明細、利用者のない ID 連携）を DB が止める。
- 付ける基準が「同じモジュールの表どうしの参照すべて」に決まり、表ごとに判断しない。

### Negative

- 参照先の表に、`(tenant_public_id, 主キー)` の一意索引が増え、書き込みのたびに更新する索引が 1 つ増える。主キーが一意なので、この索引が新しく止める重複はなく、外部キーの参照先にするためだけにある。
- テストとデータの投入は、参照先の行を先に入れる必要がある。外部キーは同じモジュールの中だけなので、用意する行もそのモジュールの中で終わる。
- 大量データの移行や投入では、参照先の表から順に入れるか、制約を外して付け直す手順が要る。
- `ON DELETE` と `ON UPDATE` の挙動を誤解すると、意図しない削除が起きうる。外部キーの changeset の書き方は、changeset の lint で確かめる。

### Neutral

- 別のモジュールの行は、これまでどおり外部キーで参照の整合性を保てず、参照先が消えた行はアプリケーションで扱う（ADR-089）。
- 新しいモジュールの表を足すときも、同じモジュールの中の参照には外部キーを作る。

## Alternatives Considered

### 選択肢1: 外部キーを作らない規約を保つ

- **Description**：同じモジュールの中でも外部キーを作らず、参照の整合性とテナントの一致をアプリケーションで保つ。
- **Pros**：制約の付け外しの手順と、参照先の一意索引が要らない。
- **Cons**：外部キーの検査は RLS を受けないため、テナントをまたぐ参照を DB が止めない。アプリのコードの 1 つの誤りが、別のテナントの行を指す参照として残る。

### 選択肢2: 参照先の主キーだけの外部キーにする

- **Description**：`ordering.t_order_line.order_id` から `ordering.t_order.order_id` への単独の列の外部キーを作る。
- **Pros**：参照先に追加の一意索引が要らない。
- **Cons**：主キーはテナントをまたいで一意なので、別のテナントの注文を指す明細を止められない。

### 選択肢3: モジュールをまたぐ参照にも外部キーを作る

- **Description**：`payment.t_payment.order_public_id` のような別のモジュールの行を指す列にも、外部キーを作る。
- **Pros**：別のモジュールの行も、参照の整合性を DB で保てる。
- **Cons**：参照先のモジュールの行の削除と変更を、参照元のモジュールの制約が止め、二つのモジュールのスキーマが結び付く（ADR-089）。

## References

- [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363)
- [issue #369: テナントでデータを分ける](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [issue #377: 業務の表に tenant_public_id と RLS を入れる](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/377)
- [PR #450](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/450)
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-089: 主キーをモジュールのスキーマと Repository の中に閉じ、外に出る値には例外なく public_id を使う](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)
- [ADR-107: 業務の表を RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- [PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md)
- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
