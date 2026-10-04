---
type: ADR
title: 'ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの UPDATE と DELETE を TableWriter に集める'
description: 楽観的ロックの版の条件、版の設定、件数の判定、55P03 の変換を shared の TableWriter に集め、ArchUnit で迂回を禁じ、版を比べない入口の件数の無視を Error Prone でコンパイルの失敗にする決定。取り込んだ PostgreSQL 設計ガイドラインの、先に SELECT ... FOR UPDATE でロックする方式を改変する。
tags: [adr, backend, database, jooq, concurrency]
---

# ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの UPDATE と DELETE を TableWriter に集める

## Status

Proposed

## Date

2026-10-04

## Context

[PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)は、[ADR-040](ADR-040-import-future-architecture-guidelines.md) で取り込んだフューチャー株式会社の PostgreSQL 設計ガイドラインに従い、先に `SELECT ... FOR UPDATE` で行をロックしてから `lock_no` を比べる方式を定めていた。
この方式では、集約ごとの `Jooq<Aggregate>Repository` に行をロックして比べる private メソッドが要り、比べる処理を書き忘れても UPDATE は成功して他の人の更新を上書きする。

この ADR の最初の版（PR #112）は、主キーと `lock_no` を条件にした UPDATE の更新件数で競合を判定し、件数を `shared` の `OptimisticLock.requireUpdated` に渡す形にした。
しかし、版の条件、版の加算、件数の受け渡し、`55P03` の変換は Repository の手書きに残った。
どれを書き忘れても UPDATE は成功し、Repository の規約のチェックリストの楽観的ロックの項目はすべて「自分で点検」だった。
`requireUpdated` を呼ぶ production のコードはまだなく、ADR も Proposed のままであるため、この版で決定を書き直す。

利用者は、共通基盤に次の順で重みを置く。
AI と開発者が迷わないこと（どこに何を書き、どの API を使うかの答えが一つ）、間違えると機械で失敗すること、システムとして安全であること（古い値の上書き、無期限の待ち、気付けない設定の誤りがない）である。

PostgreSQL の READ COMMITTED では、後の UPDATE は先の UPDATE のコミットを待ち、更新後の行で WHERE の条件を評価し直す（[PostgreSQL 18, Read Committed Isolation Level](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED)）。
このため、WHERE に期待する版を入れた UPDATE は、待った後に版が進んでいれば 0 件になる。
一方、SET の式も更新後の行で評価し直されるため、`SET lock_no = lock_no + 1` の加算だけでは古い保存を止められない。

`lock_no` は全業務テーブルにあり（[PostgreSQL の共通カラム](../database/postgresql-common-columns.md)）、列の有無では楽観的ロックの対象を分けられない。
規約は、在庫の引き当てのように版を比べない UPDATE と、`lock_no` を進めないデータパッチを認めている。

業務テーブルと production の Repository はまだないため、移行の費用は共通処理、テスト、規約に限られる。

## Decision

- 業務テーブルの UPDATE と DELETE は、`shared.infrastructure.persistence` の `TableWriter` だけが組み立てて実行する。
  期待する版（以前に読んだ集約ルートの `lock_no`）を持つ書き込みは `updateCheckingVersion` と `deleteCheckingVersion` で、持たない書き込みは `updateWhere` と `deleteWhere` で行う。
  集約の子の行の更新と削除は、ルートの書き込みが返す `LockedRoot`（削除では `DeletedRoot`）で書き、子の集合の変化は差分（削除、追加、更新）で書く。
  INSERT は `TableWriter` を通さず、`CommonColumns.forInsert` で書く。
- 業務の列の値は `ColumnValues<R>` で受け取る。
  `ColumnValues<R>` は `TableField<R, T>` を受け取る二つの `set` だけを持ち、`where` と `execute` を持たない。
  [PostgreSQL の共通カラム](../database/postgresql-common-columns.md)の 12 列を名前で照らし、渡すと `IllegalArgumentException` にする。
  規約にない `updated_reason` のような業務の列は拒否しない。
- `updateCheckingVersion` は `SET lock_no = 期待値 + 1` と `WHERE 主キー AND lock_no = 期待値` を書く。
  件数が 1 なら成功、0 なら主キーで行の有無を確かめて競合か `NoSuchElementException` に分け、2 以上なら `IllegalStateException` にする。
  `55P03` の `CannotAcquireLockException` は、それを原因に付けた競合の例外に変える。
  競合の例外は呼び出し側がメッセージと原因から作る関数（`OrderConflictException::new`）で渡し、`shared` は Domain と `error` の型に依存しない。
  期待する版が 1 未満なら、SQL を実行する前に `IllegalArgumentException` にする。
- `updateWhere` と子の更新は `SET lock_no = lock_no + 1` を書き、版を比べない更新でも版を進める。
  `updateWhere` と `deleteWhere` は件数を返し、Error Prone の `@CheckReturnValue` を付けて、戻り値の無視をコンパイルの失敗にする。
  その件数を返す Repository のメソッドにも、インタフェースで `@CheckReturnValue` を付ける。
  `updateWhere` は、業務の列が一つもなければ `IllegalArgumentException` にする。
- `CommonColumns.forUpdate` は `updated_*` だけを返し、package-private にする。
  UPDATE の `lock_no` を書くのは `TableWriter` だけになる。
- ArchUnit の `TableWriterArchTest` で次を検査する。
  - `TableWriter`、`LockedRoot`、`DeletedRoot` の外の本番のコードは、jOOQ の UPDATE、DELETE、UPSERT、MERGE の入口、`Update` と `Delete` に代入できる型の実行、`org.jooq` の型の、名前が `$` で始まるメソッド（問い合わせのモデルの API）、`UpdatableRecord` と `DAO` の書き込み、Spring JDBC と JDBC の直接の利用を呼ばない。
    判定は、呼び出し先の型が禁じる型に代入できるかで行う。
    `DataSource`、`Connection`、`ConnectionProvider` を引数に取るメソッドとコンストラクタも呼ばない。
    接続の元を受け取るライブラリ（Spring Boot の `DataSourceScriptDatabaseInitializer` など）は、パッケージを選ばずに任意の SQL を流せるため、パッケージの一覧ではなく引数の型で禁じる。
    検査の対象から外す生成コードは、jOOQ の生成先である基底パッケージ直下の `jooq` パッケージだけとし、名前に `jooq` を含む手書きのパッケージは外さない。
    `Update` と `Delete` を作る入口（`DSLContext`、`DSL`、`WithStep` の `update`、`delete`、`deleteFrom`、`updateQuery`、`deleteQuery`）をすべて禁じるため、`batch` のように作った問い合わせを受け取って実行する API は禁じなくてよい。
  - `Jooq<Aggregate>Repository` の、集約ルートを受け取る `add` 以外の public メソッドは、版を比べる入口と、引数の集約ルートの `lockNo()` を直接呼ぶ。
    名前で対象を選ばないため、`save` のような名前でも検査を外れない。
    `lockNo()` の呼び出しを求めるのは、テーブルから読み直した版を期待する版に渡すと競合を検出しないためである。
  - `domain.model` の `<Aggregate>Repository` の `add`、`update`、`delete` は、引数のない `long lockNo()` を宣言する集約ルートを一つだけ受け取る。
    集約ルートが `lockNo()` を持たないと、版を比べる規則が集約ルートを見つけられず、空のまま通るためである。
    必須の `add` を含めるため、`save` のような名前で保存する Repository でも、形を外れた集約は `add` で検出される。
  - 集約ルートを引数に取るメソッドは、版を比べない入口を呼ばない。
  - `lockNo` を持つ Command の CommandHandler は `ensureLockNo` を呼ぶ。
- jOOQ の実行時の版をテストで固定する。
  版が変わったら、[jOOQ の SQL の書き方](../database/jooq-usage.md)の「jOOQの版を上げるとき」に従って禁止の一覧を見直す。
- CommandHandler は `order.ensureLockNo(command.lockNo())` を残す。
  画面の版が古いとき、業務の検査や外部の呼び出しより先に競合として失敗させるためである。
- jOOQ の `executeWithOptimisticLocking` と `recordVersionFields` は使わない。
- `SELECT ... FOR UPDATE` の `NOWAIT` と `SKIP LOCKED` は、楽観的ロック以外の DB の行ロックとバッチのために規約に残す。
- `NoSuchElementException` と `<Aggregate>ConflictException` を 404 と 409 にする対応づけは [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107) で扱い、それまではどちらも 500 になる。

この決定は、取り込んだガイドラインを ADR-040 に従って改変し、[ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) と [ADR-050](ADR-050-define-backend-class-roles-and-naming.md) の `update` の手順を書き換える。
データパッチの規約（[PostgreSQL の共通カラム](../database/postgresql-common-columns.md)、[PostgreSQL のロックを抑えるスキーマ変更](../database/postgresql-online-schema-change.md)）と、トリガーを使わない規約（[PostgreSQL のテーブル以外の DB オブジェクト](../database/postgresql-database-objects.md)）は変えない。

## Consequences

### Positive

- Repository が書くのは業務の列の値だけになり、版の条件、版の設定、件数の判定、`55P03` の変換を書く場所がなくなる。
- 入口の選び分けは、期待する版を持っているかだけで決まる。
- jOOQ、Spring JDBC、JDBC の書き込みの API と、接続の元を受け取るライブラリを `TableWriter` の外で使うと、テスト（ArchUnit）で失敗する。
  禁止の一覧は、テストで固定した jOOQ の版の API で見直してある。
  別のテーブルの列、型の違う値、版を比べない入口の件数の無視は、コンパイルで失敗する。
- 版を比べない更新も版を進めるため、在庫の引き当てのような Java の更新が先に走れば、その前に読んだ画面の保存は上書きせずに競合として返る。
- 競合と行なしの判定は `TableWriter` の実 PostgreSQL のテストに集まり、Repository のテストは列と集約の往復と削除の範囲だけを確かめる。
- 集約ルートの UPDATE が行ロックを取るため、同じトランザクションで後から書く子の行も、`TableWriter` の経路ではルートの行ロックで守られる。

### Negative

- 次の書き込みの誤りは、機械で検出しない。
  - **Java の外の書き込み**：psql のデータパッチと Liquibase のデータ変更は、`lock_no` を進め忘れても何も止めない。
    変えない規約ではデータパッチは `lock_no` を進めないため、パッチの前に開いた画面の保存が、パッチの結果を上書きしうる。
    これは規約が受け入れているリスクとする。
  - **jOOQ の新しい書き込みの API**：jOOQ の版を上げると、ArchUnit の禁止の一覧にない書き込みの API が増えうる。
    実行時の版を固定したテストが失敗するため、「jOOQの版を上げるとき」の手順で一覧を見直す。
  - **ルートを通さない子の書き込み**：`updateWhere` と `deleteWhere` に子のテーブルを渡すことと、ルートを書かずに子を INSERT することは止められない。
    集約ルートを引数に取るメソッドからの `updateWhere` は止まるため、残るのは識別子だけを受け取るメソッドである。
  - **`long lockNo` を引数に取るメソッド**：期待する版を `long` の引数で受け取るメソッドが `updateWhere` を使う誤りは検出できない。
    ArchUnit は引数の名前を確実には読めず、`long` の引数を一律に禁じると正当な数量の引数も止まる。
    そこで規約は、期待する版を持つ書き込みを、集約ルートを受け取る `update` と `delete` に限る。
  - **別の集約ルートのテーブルへの子の書き込み**：`LockedRoot.updateChild` と `deleteChildren` は任意のテーブルを受け取る。
    別の集約ルートのテーブルを渡すと、その行を版を比べずに書く。
    子のテーブルとルートのテーブルはコードから区別できないため、レビューで見る。
  - **集約ルートの形を外れた集約**：規則は、`domain.model` にあり引数のない `long lockNo()` を宣言する型を集約ルートとみなす。
    Repository の `add`、`update`、`delete` の引数はこの形を検査する。
    `add` を持たずに `save` のような名前だけで保存する Repository と、Repository のインタフェースを経由せずに保存する集約は、形を外れても規則が空のまま通る。
  - **件数を返す Repository のメソッドの注釈**：`@CheckReturnValue` の付け忘れは検出せず、呼び出し側で件数を捨てても通る。
  - **psql による古い版での物理削除**：psql で古い版の行を削除しても、何も検出しない。
    これも規約が受け入れているリスクとする。
    手順書で `WHERE lock_no = ?` を求めることは、将来の緩和策の候補にとどめる。
  - **`ensureLockNo` に渡す値**：ArchUnit は `ensureLockNo` を呼んだかしか見ず、渡した値が `command.lockNo()` かは見ない。
  - **期待する版に渡す値**：ArchUnit は Repository が集約ルートの `lockNo()` を呼んだかしか見ず、その値を `updateCheckingVersion` と `deleteCheckingVersion` に渡したかは見ない。
    `lockNo()` を呼んだうえでテーブルから読み直した版を渡すと、競合を検出しない。
- 同じトランザクションで同じ集約を二度保存すると、二度目は期待する版が古く競合になる。
  CommandHandler は集約を一度だけ保存する規約のままにする。
- `ColumnValues` の値に別のテーブルの列の式を渡すことは、型で止まらない。
  PostgreSQL が `42P01`（FROM 句にないテーブル）で拒否するため、Repository の往復のテストで失敗する。
- 取り込んだガイドラインの方式から外れ、出典の節に改変を記す必要がある。
- 集約ルートの業務の列が変わらない更新でも集約ルートの行を書くため、WAL が増える。
- 件数が 0 の UPDATE と主キーの確認の間に他の人が行を消すと、競合ではなく「行がない」として返る。
- ロックを取れない UPDATE は、即座に失敗せず、`lock_timeout` まで待ってから競合になる（[ADR-055](ADR-055-set-db-time-limits-per-connection.md)）。

### Neutral

- 子の行は 1 行ずつ更新する。
  子の行が多い集約が出たら、`LockedRoot` の中で batch にする。
- `shared` が公開する書き込みの型は、`TableWriter`、`ColumnValues`、`LockedRoot`、`DeletedRoot` の四つになる。

## Alternatives Considered

### 選択肢1: 件数を受け取る共通処理と規約

- **Description**：この ADR の最初の版で、Repository が UPDATE を書き、件数を `OptimisticLock.requireUpdated` に渡す。
- **Pros**：共通処理が小さく、SQL が Repository に見える。
- **Cons**：版の条件、加算、件数の受け渡し、`55P03` の変換のどれを書き忘れても UPDATE は成功し、検査はレビューだけになる。

### 選択肢2: 直接の DSL と、Repository ごとの実 DB の古い保存のテスト

- **Description**：共通処理を持たず、各 Repository のテストで競合を確かめる。
- **Pros**：本番と同じ SQL を実 DB で確かめる。
- **Cons**：テストを書き忘れると何も止まらない。
  共通の契約テストの継承を ArchUnit で確かめても、フィクスチャの正しさは確かめられない。

### 選択肢3: 未実行の query を受け取る共通処理

- **Description**：Repository が組み立てた UPDATE を共通処理が実行し、件数を判定する。
- **Pros**：件数の判定の書き忘れがなくなる。
- **Cons**：WHERE と SET の中身は共通処理から見えず、版の条件と加算の書き忘れが残る。
  共通処理に渡さず `execute()` を呼ぶ迂回も止まらない。

### 選択肢4: jOOQ の UpdatableRecord と recordVersionFields

- **Description**：コード生成で `lock_no` をバージョンのカラムにし、Record の `store()` と `delete()` で版を扱う。
- **Pros**：コード生成で全テーブルに効き、`DataChangedException` を jOOQ が投げる。
- **Cons**：DSL の UPDATE と、子の更新での親の版には効かない。
  集約と Record の変換が読み取りと書き込みで別の形になり、共通カラムの値を `CommonColumns` から渡す形とも合わない。

### 選択肢5: ExecuteListener と Query Object Model で SQL を検査する

- **Description**：実行の直前に UPDATE の WHERE と SET を調べ、版の条件がなければ失敗させる。
- **Pros**：DSL の書き方を変えずに済む。
- **Cons**：jOOQ の Query Object Model は experimental であり（[jOOQ 3.21, Query object model design](https://www.jooq.org/doc/3.21/manual/sql-building/model-api/model-api-design/)）、木の走査は Open Source Edition で使えない。
  条件の書き方を網羅できず、版を比べない正当な UPDATE に抜け道の印が要る。
  JDBC の直接の利用と psql には効かない。

### 選択肢6: 先に SELECT ... FOR UPDATE NOWAIT でロックしてから比べる

- **Description**：取り込んだガイドラインのとおり、行をロックして `lock_no` を比べてから UPDATE する。
- **Pros**：ロックを取れなければ即座に失敗し、SELECT の段階で「行がない」と「他の人が更新した」を区別できる。
- **Cons**：集約ごとにロックして比べるメソッドが要り、比べる処理を書き忘れても UPDATE が成功する。
  成功する更新でも、SELECT と UPDATE の二つの文を実行する。

### 選択肢7: 共通処理が error の共通の例外を投げる

- **Description**：`TableWriter` が `error` モジュールの競合と未検出の例外を投げる。
- **Pros**：呼び出し側が例外を作る関数を渡さずに済む。
- **Cons**：`shared` が `error` に依存し、依存の向きが内側へ向かなくなる。

### 選択肢8: DB のトリガーで版の前進を強制する

- **Description**：業務テーブルに BEFORE UPDATE の行トリガーを付け、`lock_no` を 1 進めない UPDATE を拒否する。
- **Pros**：psql のデータパッチ、Liquibase のデータ変更、別のプロセスの書き込みにも効く。
- **Cons**：「トリガーは使わない」の規約（[PostgreSQL のテーブル以外の DB オブジェクト](../database/postgresql-database-objects.md)）を保つという利用者の決定により採らない。
  Java の外の書き込みの誤りは、Negative に書いた残るリスクとして扱う。

## References

- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-055: DB のロック待ち、文の実行、トランザクション中の待機の上限を接続ごとに設定する](ADR-055-set-db-time-limits-per-connection.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [jOOQ の SQL の書き方](../database/jooq-usage.md)
- [クラスの役割：jOOQ の Repository](../backend/class-roles/jooq-repository.md)
- [バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)
- [PostgreSQL 18, Read Committed Isolation Level](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED)
- [PostgreSQL 18, Explicit Locking](https://www.postgresql.org/docs/18/explicit-locking.html)
- [jOOQ, Optimistic locking](https://www.jooq.org/doc/latest/manual/sql-execution/crud-with-updatablerecords/optimistic-locking/)
- [Error Prone, CheckReturnValue](https://errorprone.info/bugpattern/CheckReturnValue)
