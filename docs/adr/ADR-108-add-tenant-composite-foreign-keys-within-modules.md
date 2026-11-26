---
type: ADR
title: 'ADR-108: 同じモジュールの表どうしの参照に、テナントを含む複合の外部キーを作る'
description: 物理の外部キーを作らない規約を改め、同じモジュールの表どうしの参照に外部キーを作る決定。RLS の表どうしは (tenant_public_id, 参照先の主キー) の複合の外部キーにして参照先に同じ組のユニークインデックスを作り、tenant_public_id を持たない tenancy の表どうしは単独の列の外部キーにする。外部キーに ON DELETE と ON UPDATE の動作を付けない。モジュールをまたぐ参照には外部キーを作らない。
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

[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md) は、業務テーブルを RLS でテナントごとに分ける。
PostgreSQL では、外部キーの検査は RLS を受けない。
外部キーがないか、参照先の主キーだけの外部キーでは、あるテナントの注文明細が別のテナントの注文を指しても、DB は止めない。
注文の主キーはテナントをまたいで一意なので、主キーの一致だけではテナントの一致を確かめられないからである。
参照元と参照先のテナントの一致を DB で止めるには、テナントを含む複合の外部キーが要る。

この点は [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363) の grilling の Q6 で決めた。
決めた時点の該当は `ordering.t_order_line.order_id` だけだった。

RLS の外に置く `tenancy` の表にも、同じモジュールの中の参照がある。
[PR #450](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/450) のレビューで、Amazon Q は `tenancy.m_user_identity.user_id` に `tenancy.m_user.user_id` への外部キーがなく、参照先のない ID 連携の行を DB が止めないと指摘した。
この指摘は、規約を改める [issue #377](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/377) で扱うことにした。

同じ形の参照は、所属の `tenancy.m_membership` の `user_id` と `tenant_id` にもある。
実装（#377）で、Decision の `tenancy` の対象を、指摘の 1 列から `tenancy` の表どうしの参照すべてに広げた。
目的は、付ける基準を「同じモジュールの表どうしの参照すべて」に揃えることである。
外部キーに `ON DELETE` と `ON UPDATE` の動作を付けないことも、同じ実装で Decision に足した。
動作を付けないことで、上に挙げた理由の 4 つ目（動作の誤解による意図しない削除）を避ける。

### 比較に使う実装の事実

[issue #611](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/611) の比較（選択肢4から6と見積もり）は、次の二つに基づく。

- 複合の外部キーは `ordering.t_order_line` の `fk_t_order_line` の一つだけで、参照先の追加のユニークインデックスは `t_order` の `uk_3_t_order` の一つだけである（changeset 019）。
- `ChildWriter` は、子の行の `tenant_public_id` を書かない。列の既定値が `current_setting('app.current_tenant')` から入れる。`TableWriter` が子へ渡す親の主キーは、RLS の下の `RETURNING` で得た値である。

## Decision

物理の外部キーを作らない規約を改め、同じモジュールの表どうしの参照に外部キーを作る。

- `tenant_public_id` を持つ表（RLS の表）どうしの参照は、`(tenant_public_id, 参照先の主キー)` の複合の外部キーにする。
  参照先には、同じ組のユニークインデックスを作る。
  今の該当は、`ordering.t_order_line` から `ordering.t_order` への参照である。
- `tenant_public_id` を持たない `tenancy` の表どうしの参照は、すべて単独の列の外部キーにする。
  今の該当は、`tenancy.m_user_identity.user_id` と `tenancy.m_membership.user_id` から `tenancy.m_user.user_id` への参照と、`tenancy.m_membership.tenant_id` から `tenancy.m_tenant.tenant_id` への参照である。
- 外部キーに `ON DELETE` と `ON UPDATE` の動作を付けず、既定の `NO ACTION` にする。
  参照される行は、参照する行を消してから消す。
- モジュールをまたぐ参照には、外部キーを作らない。
  別のモジュールの行は `<対象>_public_id` の uuid の列で指す（ADR-089 のまま）。

ADR-089 の Neutral に残していた「同じモジュールの中の外部キーの扱い」は、この ADR が決める。

規則の検査は、カタログのテストと changeset の lint で行う。
検査の範囲は[PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md#外部キー)の「外部キー」に示す。

## Consequences

### Positive

- あるテナントの行が、同じモジュールの別のテナントの行を指すと、DB が INSERT と UPDATE を拒否する。
  アプリのコードの誤りが、テナントをまたぐ参照として残らない。
- 同じモジュールの中で、参照先のない行（注文のない注文明細、利用者のない ID 連携、利用者かテナントのない所属）を DB が止める。
- 付ける基準が「同じモジュールの表どうしの参照すべて」に決まり、表ごとに判断しない。

### Negative

- 参照先の表に、`(tenant_public_id, 主キー)` のユニークインデックスが増え、書き込みのたびに更新する索引が 1 つ増える。
  主キーが一意なので、この索引が新しく止める重複はなく、外部キーの参照先にするためだけにある。
- テストとデータの投入は、参照先の行を先に入れる必要がある。
  外部キーは同じモジュールの中だけなので、用意する行もそのモジュールの中で終わる。
- 大量データの移行や投入では、参照先の表から順に入れるか、制約を外して付け直す手順が要る。
- 参照される行は、参照する行を消してからでないと消せない。
  外部キーに `ON DELETE` の動作を付けないため、DB は連鎖して消さない。
  動作を付けた changeset は、changeset の lint が止める。

### Neutral

- 別のモジュールの行は、これまでどおり外部キーで参照の整合性を保てず、参照先が消えた行はアプリケーションで扱う（ADR-089）。
- 新しいモジュールの表を足すときも、同じモジュールの中の参照には外部キーを作る。

## Alternatives Considered

### 選択肢1: 外部キーを作らない規約を保つ

- **Description**：同じモジュールの中でも外部キーを作らず、参照の整合性とテナントの一致をアプリケーションで保つ。
- **Pros**：制約の付け外しの手順と、参照先のユニークインデックスが要らない。
- **Cons**：外部キーの検査は RLS を受けないため、テナントをまたぐ参照を DB が止めない。
  アプリのコードの 1 つの誤りが、別のテナントの行を指す参照として残る。

### 選択肢2: 参照先の主キーだけの外部キーにする

- **Description**：`ordering.t_order_line.order_id` から `ordering.t_order.order_id` への単独の列の外部キーを作る。
- **Pros**：参照先に追加のユニークインデックスが要らない。
- **Cons**：主キーはテナントをまたいで一意なので、別のテナントの注文を指す明細を止められない。

### 選択肢3: モジュールをまたぐ参照にも外部キーを作る

- **Description**：`payment.t_payment.order_public_id` のような別のモジュールの行を指す列にも、外部キーを作る。
- **Pros**：別のモジュールの行も、参照の整合性を DB で保てる。
- **Cons**：参照先のモジュールの行の削除と変更を、参照元のモジュールの制約が止め、二つのモジュールのスキーマが結び付く（ADR-089）。

### 選択肢4: 親のテナントとの一致を検査するトリガーを置く

- **Description**：子の INSERT と UPDATE の前に、親の `tenant_public_id` を引いて子の列と比べるトリガーを作る。外部キーは主キー1列にする。
- **Pros**：参照先に追加のユニークインデックスが要らない。手書きの SQL とマイグレーションの INSERT も検査する。マイグレーションのロールも `FORCE ROW LEVEL SECURITY` を受けるので（[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md)）、トリガーの親の SELECT は現在のテナントの親だけを見る。
- **Cons**：検査が表の外のコードに移り、changeset を読むだけでは見えない。トリガーの本体のテストと、表ごとの追加が要る。複合の外部キーは、同じ検査を宣言だけで行うので、この案は保守の対象を増やす。

### 選択肢5: 主キーを `(tenant_public_id, id)` の複合にする

- **Description**：主キーにテナントを含め、参照先の追加のユニークインデックスを不要にする。
- **Pros**：複合の外部キーが主キーを直接指せ、参照先のユニークインデックスが増えない。
- **Cons**：[PostgreSQLの主キー](../database/postgresql-primary-keys.md)が複合主キーを使わないと定め、規則 P1 が拒否する。理由は [ADR-112](ADR-112-forbid-composite-primary-keys.md) の選択肢2に示す。`TableWriter` の `Long` の前提と規則 P1 を改める必要がある。節約できるのは索引一つの更新だけで、改める範囲に釣り合わない。

### 選択肢6: 外部キーを主キー1列にし、テナントの整合を `TableWriter` の経路とテストで守る

- **Description**：選択肢2に、`TableWriter` の経路が守る範囲を加えた案である。親の主キーは RLS の下で得た値だけを使い、別のテナントの親に結び付く書き込みを経路で作らない。
- **Pros**：参照先のユニークインデックスと複合の外部キーが要らない。通常の書き込みでは、別のテナントの親に結び付かない。
- **Cons**：守れるのは `TableWriter` を通る書き込みだけである。手書きの SQL とマイグレーションは守れない。守る範囲はテストの網羅に依存し、新しい書き込みの経路が増えるたびにテストを足す必要がある。DB の制約が残らないので、誤りは事故になってから見つかる。

### 見積もり

#### DB で止めたい事故の範囲

`BYPASSRLS` のロールは、[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md)が使わないと定めているので、範囲に含めない。
止める範囲を、書き込みの経路ごとに示す。

- `TableWriter`：複合の外部キー、選択肢4、選択肢6の三つとも止める（選択肢6は、経路が別のテナントの親を作らない）。
- 手書きの SQL：複合の外部キーとトリガーは止める。選択肢6は止めない。
- マイグレーション（マイグレーションのロール）：複合の外部キーとトリガーは止める。選択肢6は止めない。

#### テーブルが増えたときの索引の数

推論で、試作と計測はしていない。

- 複合の外部キーで参照される親の表ごとに、その表に `(tenant_public_id, 主キー)` のユニークインデックスが一つ増える。同じ親を指す子が複数あっても一つで済む。今は `uk_3_t_order` の一つだけである。
- 複合の外部キーで参照される親の表が N 個になると、増える索引は N 個である。
- 追加の索引は、親の INSERT のたびに一つの索引の行を足す。DELETE と、`tenant_public_id` か主キーを変える UPDATE も更新する。書き込みの遅さは測っていないので、増える割合は不明である。
- 選択肢4と6は、この索引を作らない代わりに、トリガーの本体か経路のテストを N 個分書く。1本の索引と N 個のテストの大きさは比べていない。

#### 今の判断

親の表が一つの今は、複合の外部キーの追加のコストは索引一つである。他の案の保守の負担は測っておらず、この比較は定性的である。
Decision は変えない。
Status を Accepted にするには、上の一覧の「手書きの SQL」と「マイグレーション」を、DB で止めたい事故に含めるかの合意が要る。
含めないと決めた場合は、この ADR を選択肢6に書き直し、規則 K1（`SchemaTenantConventionsTest`）、`AggregateDeclarationConventionsTest`、[PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md)の外部キーの節も同じ変更で改める。

## References

- [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363)
- [issue #369: テナントでデータを分ける](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [issue #611](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/611)
- [issue #377: 業務の表に tenant_public_id と RLS を入れる](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/377)
- [PR #450](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/450)
- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-089: 主キーをモジュールのスキーマと Repository の中に閉じ、外に出る値には例外なく public_id を使う](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)
- [ADR-107: 業務テーブルを RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- [ADR-112: 複合主キーを使わず、業務キーの組にはユニークインデックスを張る](ADR-112-forbid-composite-primary-keys.md)
- [PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md)
- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
