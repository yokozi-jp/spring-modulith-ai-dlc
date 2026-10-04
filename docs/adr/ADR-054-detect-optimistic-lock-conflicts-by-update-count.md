---
type: ADR
title: 'ADR-054: 楽観的ロックの競合を UPDATE の条件の lock_no と更新件数で判定する'
description: 楽観的ロックを、主キーと lock_no を条件にした UPDATE の更新件数で判定する方式に変え、件数の判定を shared の OptimisticLock に置く決定。取り込んだ PostgreSQL 設計ガイドラインの、先に SELECT ... FOR UPDATE でロックする方式を改変する。
tags: [adr, backend, database, jooq, concurrency]
---

# ADR-054: 楽観的ロックの競合を UPDATE の条件の lock_no と更新件数で判定する

## Status

Proposed

## Date

2026-10-03

## Context

[PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)は、[ADR-040](ADR-040-import-future-architecture-guidelines.md) で取り込んだフューチャー株式会社の PostgreSQL 設計ガイドラインに従い、先に `SELECT ... FOR UPDATE` で行をロックしてから `lock_no` を比べる方式を定めていた。
この方式は `NOWAIT` を付けられ、SELECT の段階で「行がない」と「他の人が更新した」を区別できるため、採ってきた。

一方、この方式では集約ごとの `Jooq<Aggregate>Repository` に、行をロックして比べる 20 行ほどの private メソッド（`lockOrder`）が要る。
比べる処理を書き忘れても、UPDATE はそのまま成功して他の人の更新を上書きする。
Doma の `@Version` による更新は、識別子とバージョンを UPDATE の条件に入れて 1 加算し、更新件数が 0 なら楽観的ロックの失敗にする（[Doma, Update](https://docs.domaframework.org/en/latest/query/update/)）。
同じ方式なら、Repository は UPDATE の条件に `lock_no` を足し、件数を共通処理に渡すだけで済む。

PostgreSQL の READ COMMITTED では、UPDATE は対象の行のロックを取り、他のトランザクションが更新中の行ならその終了を待つ。
待った後は更新後の行で WHERE の条件を評価し直すため、`lock_no` が進んでいれば件数は 0 になる（[PostgreSQL, Read Committed Isolation Level](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED)）。

[ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) は、楽観的ロックの方式を新しい ADR で決め直すとしていた。
`lock_no` の設定と加算は、`shared` の `CommonColumns` の `forInsert` と `forUpdate` がすでに担っている。

## Decision

楽観的ロックの `update` は、次の方式で書く。

- 集約ルートの行を `UPDATE ... SET ..., lock_no = lock_no + 1 WHERE 主キー = ? AND lock_no = ?` で更新する。
  条件の `lock_no` は集約の `lockNo()` にし、`lock_no` の加算は `CommonColumns.forUpdate` が返す値で書く。
  Repository は `LOCK_NO` を読むだけにし、書かない。
- 集約ルートの行を先に更新し、子の行は後で更新する。
  集約ルートの業務の列が変わらなくても、集約ルートの行を更新して `lock_no` を加算する。
- 更新件数は `shared.infrastructure.persistence` の `OptimisticLock.requireUpdated(更新件数, テーブル, 主キーの条件, 競合の例外を作る関数)` で判定する。
  1 件なら何もしない。
  0 件なら主キーで `fetchExists` を実行し、行がなければ `NoSuchElementException` を、行があれば渡された関数が作る例外（`OrderConflictException::new`）を投げる。
  2 件以上なら、主キーの条件が 1 行を特定していないため `IllegalStateException` を投げる。
- `OptimisticLock` は Domain と `error` の型に依存しない。
- 集約ルートの UPDATE が `lock_timeout` でロックを取れず SQLSTATE `55P03` で失敗すると、Spring Boot の jOOQ の例外の変換が `CannotAcquireLockException` を投げる。
  Repository はこれを catch し、原因に付けた `<Aggregate>ConflictException` を投げる。
  `requireUpdated` は件数だけを受け取るため、この変換は Repository に置く。
- CommandHandler は `order.ensureLockNo(command.lockNo())` を残す。
  CommandHandler は集約を DB から読み直すため、`ensureLockNo` は画面の値と読んだ値を比べ、UPDATE の条件は読んだ値と更新の時点の行の値を比べる。
- jOOQ の `executeWithOptimisticLocking` と `recordVersionFields` は使わない。
  これらは `UpdatableRecord.store()` でしか働かず、Repository は UPDATE を DSL で書く。
- `SELECT ... FOR UPDATE` の `NOWAIT` と `SKIP LOCKED` は、楽観的ロック以外の DB の行ロックとバッチのために規約に残す。
- `NoSuchElementException` と `<Aggregate>ConflictException` を 404 と 409 にする対応づけは [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107) で扱い、それまではどちらも 500 になる。

この決定は、取り込んだガイドラインを ADR-040 に従って改変し、[ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) と [ADR-050](ADR-050-define-backend-class-roles-and-naming.md) の `update` の手順を書き換える。

## Consequences

### Positive

- 集約ルートの UPDATE が行ロックを取るため、同じトランザクションで後から更新する子の行も、集約ルートの行ロックで守られる。
- 「行がない」と「他の人が更新した」を区別する SELECT は、件数が 0 のときだけ実行する。
  成功する更新は UPDATE だけで済む。
- 集約ごとの `lockOrder` がなくなり、件数の判定が `requireUpdated` の一か所になる。
  判定の書き忘れは、Repository の規約のチェックリストと、競合と行がないことを確かめる Repository のテストで見つける。
- `NOWAIT` の代わりに `lock_timeout` がロック待ちの上限になる（[ADR-055](ADR-055-set-db-time-limits-per-connection.md)）。

### Negative

- 取り込んだガイドラインの方式から外れ、出典の節に改変を記す必要がある。
- 集約ルートの業務の列が変わらない更新でも、集約ルートの行を書くため、WAL が増える。
- 件数が 0 の UPDATE と `fetchExists` の間に他の人が行を消すと、競合ではなく「行がない」として返る。
- ロックを取れない UPDATE は、即座に失敗せず、`lock_timeout` まで待ってから競合になる。

### Neutral

- `requireUpdated` の呼び忘れは機械では検査しない。
  集約ができて呼び忘れが問題になったら、ArchUnit の規則を検討する。

## Alternatives Considered

### 選択肢1: 先に SELECT ... FOR UPDATE NOWAIT でロックしてから比べる

- **Description**：取り込んだガイドラインのとおり、行をロックして `lock_no` を比べてから UPDATE する。
- **Pros**：ロックを取れなければ即座に失敗し、SELECT の段階で「行がない」と「他の人が更新した」を区別できる。
- **Cons**：集約ごとに `lockOrder` が要り、比べる処理を書き忘れても UPDATE が成功して他の人の更新を上書きする。
  成功する更新でも、SELECT と UPDATE の二つの文を実行する。

### 選択肢2: jOOQ の recordVersionFields と store() を使う

- **Description**：コード生成で `lock_no` をバージョンのカラムにし、`UpdatableRecord.store()` で更新する。
- **Pros**：jOOQ が件数を判定し、`DataChangedException` を投げる。
- **Cons**：Repository は UPDATE を DSL で書いており、`store()` を使うと集約と Record の変換の形が変わる。
  共通カラムの値を `CommonColumns` から渡す形とも合わない。

### 選択肢3: 件数を各 Repository で判定する

- **Description**：`shared` に共通処理を置かず、各 Repository が件数を見て例外を投げる。
- **Pros**：`shared` の公開する型が増えない。
- **Cons**：0 件のときの行の有無の確かめ方と例外のメッセージが Repository ごとに食い違い、判定を書き忘れやすい。

### 選択肢4: 共通処理が error の共通の例外を投げる

- **Description**：`OptimisticLock` が `error` モジュールの競合と未検出の例外を投げる。
- **Pros**：呼び出し側が例外を作る関数を渡さずに済む。
- **Cons**：`shared` が `error` に依存し、依存の向きが内側へ向かなくなる。
  集約の `Jooq<Aggregate>Repository` が `error` の型に依存すると、`infrastructureDependsOnlyOnDomainModel` の規則にも反する。

### 選択肢5: 共通処理が UPDATE も実行する

- **Description**：`OptimisticLock` が UPDATE を組み立てて実行し、`55P03` の変換と件数の判定をまとめて担う。
- **Pros**：`CannotAcquireLockException` の変換も一か所になる。
- **Cons**：業務の列の書き方と子の行の更新を共通処理に渡す形が要り、SQL が Repository から離れる。
  共通処理を件数の判定だけにすれば、Repository の SQL はそのまま読める。

## References

- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-055: DB のロック待ち、文の実行、トランザクション中の待機の上限を接続ごとに設定する](ADR-055-set-db-time-limits-per-connection.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [クラスの役割：jOOQ の Repository](../backend/class-roles/jooq-repository.md)
- [PostgreSQL, Read Committed Isolation Level](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED)
- [PostgreSQL, Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- [jOOQ, Optimistic locking](https://www.jooq.org/doc/latest/manual/sql-execution/crud-with-updatablerecords/optimistic-locking/)
- [Doma, Update](https://docs.domaframework.org/en/latest/query/update/)
