---
type: ADR
title: 'ADR-129: 履歴の表を宣言した子は、削除する前の行を TableWriter が同じトランザクションで履歴の表へ写す'
description: 集約の子の行を TableWriter.update で物理削除すると内容が残らない問題に対し、追跡が必要な子だけ ChildTable に履歴の表を宣言し、ChildWriter が削除の前に同じトランザクションで行を写す決定。追跡する範囲、履歴の表の形、親の物理削除の入口を足すときの前提、追跡しない子の理由を記録する。
tags: [adr, backend, database, jooq, audit]
---

# ADR-129: 履歴の表を宣言した子は、削除する前の行を TableWriter が同じトランザクションで履歴の表へ写す

## Status

Proposed

## Date

2026-11-25

## Context

`TableWriter.update` は、渡した子の自然キーにない保存済みの子を物理削除する（[ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
削除した行の内容は、どこにも残らない（[issue #609](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/609)）。
共通カラムと親の `updated_*` は「いつ、誰が、どの処理で親を更新したか」を示すが、何が消えたかは示さない。
pgAudit は保守運用者の操作だけを対象にし、アプリケーションの DELETE を記録しない。

規約は、物理削除する行を、誤操作からの復元や監査での参照が要件にあるときだけアーカイブテーブルへ退避するとする（[PostgreSQLのテーブル論理設計](../database/postgresql-logical-design.md#論理削除)、[ADR-113](ADR-113-forbid-logical-delete-flags.md)）。
退避はトリガーでなくアプリケーションが書く（[PostgreSQLの時間で変わる業務データ](../database/postgresql-temporal-data.md#トリガー)）。

現在の子は三つある。

- `tenancy.m_membership`：テナントへの所属と役割。外すと、誰がどの役割でいつまで所属していたかが消える。権限の調査と監査で参照する。
- `tenancy.m_user_identity`：IdP の `issuer_url` と `subject_code` を持つ個人データ。
- `ordering.t_order_line`：注文の明細。

追跡が必要な場面の決定が、Issue の依存事項に挙がっている。
この ADR を起こす時点で、利用者との対話では決めていないため、次の前提を置く。
前提が違えば、この ADR を書き直す。

- 想定する場面は、誤操作の復元ではなく、監査と障害の調査である。削除した行を後から読めればよく、アプリケーションが自動で戻す機能は作らない。
- 追跡する範囲は、削除すると権限や取引の根拠が消える子に限る。

親（集約ルート）の物理削除は、`TableWriter` に入口がなく、本番の呼び出しもない（ADR-054）。
入口を足すとき、親と一緒に消える子も追跡できる設計でなければ、子の追跡が親の削除で抜ける。

## Decision

追跡が必要な子だけ、`ChildTable.archivedTo(履歴の表)` で履歴の表を宣言する。
`ChildWriter` は、履歴の表がある子を削除する前に、削除する行を `INSERT ... SELECT` の 1 文で履歴の表へ写す。
写した行数が削除する行数と違えば `IllegalStateException` にし、削除の前に失敗させる。
写す文と削除の文は、`TableWriter` が要求する同じトランザクションで実行するため、片方だけが成功しない。

履歴の表は次の形にする。

- 名前は種別の接頭辞 `h_` を付ける（`tenancy.h_membership`）。
- 子のテーブルのすべての列を、同じ名前と型で持つ。共通カラムも、削除前の値のまま写す。作成者と、最後の更新者が残る。
- 退避の `archived_at`、`archived_by`、`archived_pgm_cd`、`archived_tx_id` を持つ。値は共通カラムと同じ規則で `CommonColumns` が作る。
  `deleted_at` にしないのは、論理削除のカラム名を拒否する規則 N11 に当たらないためと、この列が削除の印ではなく退避の記録だからである。
- 子の主キーと自然キーに一意制約を付けない。同じ自然キーの子を作り直して消しても、書ける。代わりに自前の主キー（`membership_history_id`）を持つ。
- 外部キーを張らない。親や利用者の行が後からなくなっても、履歴は残す。
- 宣言は、列の名前と型、退避のカラムの有無を、Repository のクラスが読み込まれるときに検査し、足りなければ `IllegalArgumentException` にする。

追跡する子は、次のとおり決める。

- `m_membership` は追跡する（`h_membership`）。
- `m_user_identity` は追跡しない。個人データを履歴の表へ複製すると、保持期限と削除の義務が二重になる。保持期限は [#672](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/672) で決めるまで、複製しない。
- `t_order_line` は追跡しない。確定後の注文の明細は消さず、取り消しは注文の状態で表す。

親の物理削除の入口を足すときは、次を満たす。

- 親と一緒に消える子を、削除する前に同じ宣言の履歴の表へ写す。写す処理は `ChildWriter` に置き、`ON DELETE CASCADE` で子を消さない。
- 親の行自体を残す要否は、そのときの ADR-054 の更新で決める。

## Consequences

### Positive

- 追跡する子は、削除した行の内容を、退避した日時、者、処理、trace と一緒に後から引ける。
- 退避と削除が同じトランザクションにあり、片方だけが成功しない。
- Repository は宣言を 1 行足すだけで済み、削除の呼び出しを変えない。
- 履歴の表の列は、宣言の検査が子の列との食い違いを起動時に止める。

### Negative

- 追跡する子ごとに、履歴の表の changeset と jOOQ の生成物が要る。
- 子の列を足す changeset は、履歴の表の列も足す。足し忘れは、宣言の検査が起動時に止める。
- 履歴の表は、削除のたびに行が増える。保持期限は [#672](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/672) の対象になる。
- 履歴の表に自動で復元する処理はない。復元は運用者が履歴を読んで行う。

### Neutral

- 子は保存のたびに残る子も UPDATE する（[PostgreSQLの共通カラム](../database/postgresql-common-columns.md)）。履歴の表に写すのは、消える子だけである。
- 親の物理削除は、入口を足すときに別の判断になる。

## Alternatives Considered

### 選択肢1: 業務上の状態で表し、削除しない

- **Description**：「キャンセル」「廃盤」のように、状態のカラムで表す。
- **Pros**：`TableWriter.update` でそのまま書け、履歴の表が要らない。
- **Cons**：所属の取り外しは、状態でなく関係の消滅であり、「外れた」という状態を持たせると、一意制約（利用者とテナントの組）が、外れた後の再所属とぶつかる。注文の明細のように、状態で表せる子は、この ADR の対象にしない。

### 選択肢2: 変更履歴を追記する履歴テーブル

- **Description**：削除だけでなく追加と更新も履歴テーブルに追記する。
- **Pros**：所属の役割の変更も追える。
- **Cons**：今回の問題は削除の追跡であり、更新の追跡は要件にない。追記の量が増える。更新の追跡が要るときは別の ADR で決める。

### 選択肢3: DB のトリガーで退避する

- **Description**：子の DELETE に、行を履歴の表へ写すトリガーを付ける。
- **Pros**：psql による削除も記録できる。
- **Cons**：トリガーを使わない規約に反する。トリガーは共通カラムの値（利用者、処理、trace）をアプリケーションから受け取れない。

### 選択肢4: Repository が履歴を書く

- **Description**：子を消す Repository のメソッドが、履歴の表へ INSERT する。
- **Pros**：`TableWriter` を変えない。
- **Cons**：業務テーブルへの書き込みを `TableWriter` に集める決定（ADR-054）に反し、ArchUnit の検査が止める。書き忘れを型で防げない。

## References

- [issue #609](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/609)
- [ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの INSERT と UPDATE を集約の宣言から TableWriter が書く](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)
- [ADR-113: 論理削除フラグを作らず、業務上の意味を持つカラムか物理削除で表す](ADR-113-forbid-logical-delete-flags.md)
- [PostgreSQLのテーブル論理設計](../database/postgresql-logical-design.md)
- [PostgreSQLの排他制御](../database/postgresql-concurrency-control.md)
- [テーブルごとの保持期限と個人データの有無の一覧](../database/table-retention-and-personal-data.md)
