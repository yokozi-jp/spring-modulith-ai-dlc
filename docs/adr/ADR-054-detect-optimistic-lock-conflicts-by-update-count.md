---
type: ADR
title: 'ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの INSERT と UPDATE を集約の宣言から TableWriter が書く'
description: 楽観的ロックの版の条件、版の設定、件数の判定、55P03 と 23505 の変換を shared の TableWriter に集め、Repository が宣言する集約（AggregateTable と ChildTable）と保存後の子の全行から、ルートの INSERT と UPDATE、子の差分の書き込みを TableWriter が行い、期待する版を Versioned の集約ルートから読み、競合を shared.concurrency の ConflictException で 409 にし、画面の版を ExpectedLockNo と VersionedCommand で運び、ArchUnit で迂回と検査の抜けを禁じる決定。取り込んだ PostgreSQL 設計ガイドラインの、先に SELECT ... FOR UPDATE でロックする方式を改変する。
tags: [adr, backend, database, jooq, concurrency, http]
---

# ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの INSERT と UPDATE を集約の宣言から TableWriter が書く

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
その後、[ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md) の改訂（[issue #145](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/145)）に合わせ、`55P03` の変換で原因を付けないよう、この版で決定を書き直す。
また、ADR-062 が集約ルートの INSERT の入口 `TableWriter.insert` を足したため、INSERT は `TableWriter` を通さないとしていた決定を、この版で書き直す。
その後、ArchUnit がラムダの本体の呼び出しを外側のメソッドの呼び出しとして数えると分かったため、R4 の記述を、この版で書き直す。
さらに、Repository が 5 つの手順（ルートの更新、内部の主キーの読み直し、保存済みの子の読み取り、子の削除、子ごとの更新か追加）を並べ、子の書き込みが任意の `Condition` を受け取り、INSERT が別の弱い入口になっていたため、入口を集約の宣言と `insert`、`update` の二つにまとめる決定を、この版で書き直す。

利用者は、共通基盤に次の順で重みを置く。
AI と開発者が迷わないこと（どこに何を書き、どの API を使うかの答えが一つ）、間違えると機械で失敗すること、システムとして安全であること（古い値の上書き、無期限の待ち、気付けない設定の誤りがない）である。

PostgreSQL の READ COMMITTED では、後の UPDATE は先の UPDATE のコミットを待ち、更新後の行で WHERE の条件を評価し直す（[PostgreSQL 18, Read Committed Isolation Level](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED)）。
このため、WHERE に期待する版を入れた UPDATE は、待った後に版が進んでいれば 0 件になる。
一方、SET の式も更新後の行で評価し直されるため、`SET lock_no = lock_no + 1` の加算だけでは古い保存を止められない。

`lock_no` は全業務テーブルにあり（[PostgreSQL の共通カラム](../database/postgresql-common-columns.md)）、列の有無では楽観的ロックの対象を分けられない。
規約は、在庫の引き当てのように版を比べない UPDATE と、`lock_no` を進めないデータパッチを認めている。

最初の版の時点では業務テーブルと production の Repository がなく、移行の費用は共通処理、テスト、規約に限られていた。
その後、`t_order` と `t_payment` の業務テーブルと、`JooqOrderRepository`、`JooqPaymentRepository`、`JooqProductRepository` が加わった。
書き込む `JooqOrderRepository` と `JooqPaymentRepository` は、この決定の入口を使っている。

## Decision

- 業務テーブルの INSERT と UPDATE は、`shared.persistence` の `TableWriter` だけが組み立てて実行する。
  Repository は集約の形を、クラスの定数として一度だけ宣言する。
  `AggregateTable<R, K, A>` は、ルートのテーブル、ルートを一つに決める一意の列（`public_id` など）、集約ルートからその列の値を取る関数、内部の主キー、子のテーブルを持つ。
  `A` は集約ルートのクラス（`Versioned` を実装する）で、`insert` と `update` が受け取る集約ルートの型になる。
  一意の列の値は関数で集約ルートから取るため、呼び出し側が別の集約ルートの値と組にして渡す誤りは書けない。
  `ChildTable<C>` は、子のテーブル、親の内部の主キーを持つ外部キーの列、親の中で行を同定する自然キーの列（一つ以上）を持つ。
  `ChildTable` はどの集約の子かを型に持たない。`TableWriter` は、渡された子の行が宣言した子と過不足なく合うことを、SQL の前に実行時に確かめる（`IllegalArgumentException`）。
  同じ子のテーブルを二つの集約の子として宣言する誤りと、子のテーブルを別の集約のルートとして宣言する誤りは、この確かめで見つからないため、`AggregateDeclarationsTest` が本番の全 Repository の宣言を調べる。
  子を親の子として宣言し忘れる誤りと、`parentKey` の列の誤りは、`AggregateDeclarationInspectionTest` が宣言を DB の外部キーと突き合わせて止める（同じモジュールの外部キーは、参照する表が参照される表をルートとする集約の子であることと、`parentKey` が外部キーの列に含まれることを求める）。
  子の自然キーの列は、保存済みの値と `equals` で比べるため、`Integer`、`Long`、`Short`、`String`、`UUID` に限る。
  宣言は構造と、ルートを一つに決める値の取り方（`keyOf`）だけを持ち、業務の値から列への変換は、保存のたびに渡すラムダが持つ。
  Repository は、保存のたびにルートの列の値と、保存後にあるべき子の全行（`ChildRows`）を渡す。
  `Condition`、`Insert`、`Update` は渡さない。ルートの条件、版の条件、子の外部キーの条件は、`TableWriter` が宣言から作る。
- `TableWriter.insert` は、新しい集約ルートを受け取り、ルートを `INSERT ... RETURNING 内部の主キー` で書き、続けて子を 1 文の複数行の INSERT で書く。
  `lock_no = 1` と共通カラムは `TableWriter` が書く。
  内部の主キーは `RETURNING` で受けるため、Repository は読み直さない。
  子を宣言していない集約は、子の行を渡さない。
- `TableWriter.update` は、以前に読んだ集約ルート（`AggregateTable` の `A`、`shared.concurrency` の `Versioned`、`long lockNo()`）を受け取り、その `lockNo()` を期待する版にする。
  版の数値を渡す入口はなく、`A` に集約ルートのクラスを指定すれば、版を返すラムダも渡せないため、テーブルから読み直した版を期待する版に渡す誤りは書けない。
  `A` に `Versioned` そのものを指定すると、ラムダを渡せる。集約ルートのクラスを指定することはレビューで見る。
  Repository が読み直した版で `Order.restore(...)` のように集約ルートを作り直して渡す誤りは、型では止まらない。これもレビューで見る。
  ルートは `SET 業務の列, updated_*, lock_no = 期待値 + 1` と `WHERE 一意の列 AND lock_no = 期待値` の UPDATE で書き、`RETURNING 内部の主キー` で受ける。
  件数が 1 なら成功、0 なら一意の列で行の有無を確かめて `ConflictException`（種類は `VERSION`）か `NotFoundException` に分け、2 以上なら `IllegalStateException` にする。
  期待する版が 1 未満なら、SQL を実行する前に `IllegalArgumentException` にする。
  業務の列が一つもなくても、版と `updated_*` を進める。
- `update` は、ルートの行ロックを取った後に、子を差分で書く。手順は[PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)を正とする。
  残る子の `lock_no` は 1 進む。
  宣言した子すべての `ChildRows` を渡さないと、`IllegalArgumentException` にする。
  渡さないことを「変更なし」と読むと、消えるべき子が残るためである。
  自然キーが `null` か重複する、行ごとに書く列が違う、宣言していない子を渡すといった不備も、SQL を実行する前に `IllegalArgumentException` にする。
  残る子の更新が 1 行でなければ `IllegalStateException` にする。ルートの行ロックの下で読んだ自然キーに対する更新なので、0 件や 2 件以上は起こらない状態である。
- 業務の列の値は `ColumnValues<R>` で受け取る。
  `ColumnValues<R>` は `TableField<R, T>` を受け取る二つの `set` だけを持ち、`where` と `execute` を持たない。
  [PostgreSQL の共通カラム](../database/postgresql-common-columns.md)の 12 列と、宣言から `TableWriter` が書く列（ルートの一意の列、内部の主キー、子の親の外部キー）を渡すと、`IllegalArgumentException` にする。
  子の自然キーの列は、行の同定に使うため渡す。
  規約にない `updated_reason` のような業務の列は拒否しない。
- 例外は、Spring の例外の型ではなく SQLSTATE で `ConflictException` に変える。
  `55P03`（行ロックを `lock_timeout` までに取れない）は `Kind.LOCK` に、`23505`（一意制約の違反）は `Kind.UNIQUE` に、原因を付けずに変える（ADR-062）。
  ルート、子の UPDATE、DELETE、INSERT のすべての文に同じ変換を使う。
  Spring は PostgreSQL の `21000`（サブクエリが 2 行以上を返した）も重複キーの例外にまとめるため、型で変換すると、コードの誤りが 409 になる。
  それ以外の SQLSTATE は変換せずに投げる。
  競合の例外は `shared.concurrency` の `ConflictException` で、`TableWriter` が自分で投げる。
- 書き込みは、開いていて読み取り専用ではないトランザクションの中でだけ行う。そうでなければ、SQL を実行する前に `IllegalStateException` にする。
  ルートの行ロックが子の書き込みを守るのは、同じトランザクションの中だけだからである。
- 版を比べない UPDATE と DELETE の入口と、集約ルートの物理削除の入口は、今は持たない。
  本番の呼び出しがなく、`Condition` を受け取る入口は、版の比べ忘れと子の絞り込み忘れの抜け道になるためである。
  在庫の引き当てのように版を比べない更新が必要になったとき、この ADR を更新して、集約と一意の列で行を指し、件数の意味を結果の型で返す入口を足す。
- `CommonColumns.forUpdate` は `updated_*` だけを返し、package-private にする。
  UPDATE の `lock_no` を書くのは `TableWriter` だけになる。
  `CommonColumns.forInsert` は、テストのデータ作成のために public のままにするが、本番の Repository は `insertInto` を呼べないため使えない（H1）。
- ArchUnit の `TableWriterArchTest` で次を検査する。
  - `TableWriter` と、その子の行の書き込み（`ChildWriter`）の外の本番のコードは、jOOQ の INSERT、UPDATE、DELETE、UPSERT、MERGE の入口、`Update` と `Delete` に代入できる型の実行、`org.jooq` の型の、名前が `$` で始まるメソッド（問い合わせのモデルの API）、`TableRecord`、`UpdatableRecord`、`DAO` の書き込み、Spring JDBC と JDBC の直接の利用を呼ばない。
    判定は、呼び出し先の型が禁じる型に代入できるかで行う。
    `DataSource`、`Connection`、`ConnectionProvider` を引数に取るメソッドとコンストラクタも呼ばない。
    接続の元を受け取るライブラリ（Spring Boot の `DataSourceScriptDatabaseInitializer` など）は、パッケージを選ばずに任意の SQL を流せるため、パッケージの一覧ではなく引数の型で禁じる。
    検査の対象から外す生成コードは、jOOQ の生成先である基底パッケージ直下の `jooq` パッケージだけとし、名前に `jooq` を含む手書きのパッケージは外さない。
    除外はクラスの場所で判定し、そのパッケージに置いた手書きのクラスもすべての検査を外れるため、そのパッケージには jOOQ のコード生成の出力だけを置く。
    `ProductionCodeOnlyTest` は、そのパッケージの本番のクラスのソースファイルがすべて `src/generated/jooq` にあることを確かめる。
    `Insert`、`Update`、`Delete` を作る入口（`DSLContext`、`DSL`、`WithStep` の `insertInto`、`insertQuery`、`batchInsert`、`update`、`delete`、`deleteFrom`、`updateQuery`、`deleteQuery`）をすべて禁じるため、`batch` のように作った問い合わせを受け取って実行する API は禁じなくてよい。
  - `repositoryUpdateCallsVersionedEntryPoint`（H3）：`Jooq<Aggregate>Repository` の、集約ルートを受け取る `add` 以外の public メソッドは、`update` という名前で、`TableWriter.update` を直接呼ぶ。
    `save` や `delete` のような名前は検出する。範囲は[アーキテクチャテスト](../backend/architecture-tests.md)を正とする。
  - `domain.model` の `<Aggregate>Repository` の `add`、`update`、`delete` は、`Versioned` を実装する集約ルートを一つだけ受け取る。
    集約ルートが `Versioned` を実装しないと、版を比べる規則が集約ルートを見つけられず、`TableWriter.update` にも渡せないためである。
    必須の `add` を含めるため、`save` のような名前で保存する Repository でも、形を外れた集約は `add` で検出される。
  - R1 と R2 は、`domain.model` の Repository の `add` 以外の書き込み（`update`、`delete`、集約ルートを受け取るメソッド）を同じ判定で見つける。
    `Jooq<Aggregate>Repository` の検査と同じく集約ルートの引数で選ぶため、`save` のような別名でも規則を外れない。
  - `commandsBuiltByPresentationForWritesAreVersioned`（R1）：presentation が作った Command を受け取り、Repository の `add` 以外の書き込みを呼ぶ CommandHandler の Command は、`VersionedCommand` を実装する。
    presentation が作るとは、コンストラクタかコンストラクタ参照を呼ぶか、Command 自身を返す static factory を呼ぶかメソッド参照で使うことである。
    Request と Listener の両方が作る Command は、Request 用と Listener 用に分ける。
    Listener が作る Command は版を持たなくてよい。
  - `onlyCommandHandlersUpdateOrDeleteAggregates`（R2）：CommandHandler 以外のクラスは、`domain.model` の Repository の `add` 以外の書き込みを呼ばない。
    Domain Service、QueryService、Repository の実装からの保存を止める。
    Repository の実装から別の Repository の書き込みを呼ぶ経路も、CommandHandler の `ensureLockNo` を通らないため止める。
  - `expectedLockNoIsCreatedOnlyByRequests`（R3）：`ExpectedLockNo` のコンストラクタ（`ExpectedLockNo::new` を含む）は、`presentation.web` の `*Request` だけが呼ぶ。
    Repository、`TableWriter`、CommandHandler、Controller が読んだ版から作って、比較を常に成り立たせることを止める。
  - `commandHandlersEnsureScreenLockNo`（R4）：CommandHandler の、引数が `VersionedCommand` である `handle` は、その中で直接 `domain.model` の型の `ensureLockNo(ExpectedLockNo)` を呼ぶ。
    別のメソッドの中の呼び出しと、`ensureLockNo(long)` のオーバーロードでは満たせない。ラムダの本体の呼び出しは `handle` の呼び出しとして数えるため、満たす。
- jOOQ の実行時の版をテストで固定する。
  版が変わったら、[jOOQ の SQL の書き方](../database/jooq-usage.md)の「jOOQの版を上げるとき」に従って禁止の一覧を見直す。
- 期待する版は `shared.concurrency` の `ExpectedLockNo`（`long value`、1 未満は `IllegalArgumentException`）で運ぶ。
  既存の集約を変える Command は `VersionedCommand`（`ExpectedLockNo expectedLockNo()`）を実装する。
  HTTP の Request と OpenAPI は数値の `@Min(1) long lockNo` のままにし、Request の `toCommand` で `ExpectedLockNo` に変換する。
  `ExpectedLockNo` は `shared.persistence` に置かない。
  `TableWriter.update` は `ExpectedLockNo` を受け取らず、集約ルートの `Versioned.lockNo()` から版を読む。画面の版は CommandHandler の `ensureLockNo` で比べる。
- CommandHandler は `order.ensureLockNo(command.expectedLockNo())` を残す。
  画面の版が古いとき、業務の検査や外部の呼び出しより先に競合として失敗させるためである。
  集約の `ensureLockNo(ExpectedLockNo)` は、違えば `ConflictException` を投げる。
- jOOQ の `executeWithOptimisticLocking` と `recordVersionFields` は使わない。
- `SELECT ... FOR UPDATE` の `NOWAIT` と `SKIP LOCKED` は、楽観的ロック以外の DB の行ロックとバッチのために規約に残す。
- `ApiExceptionHandler` は `ConflictException` を 409 の Problem Details にする。
  `lock_timeout` で `55P03` から変えた競合も同じ 409 である。
  本文に例外の detail、型、スタックトレース、ID、SQL を出さない。
  ログは INFO で、`setCause` で例外を付ける。
  期待された 4xx は ERROR のスタックトレースで重複して記録しない規約（[可観測性の規約](../observability/conventions.md)）に従うためである。
  WARN 以上は起動時と Collector の障害の調査に使う標準出力のログにも流れるため、競合で埋めないよう WARN にもしない。
  409 の種類は `conflict.kind` で記録する（ADR-062）。
- 404 と 422 の対応づけは [ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md) で決める。
  `IllegalStateException` は競合に使わず、409 にも対応づけない。

この決定は、取り込んだガイドラインを ADR-040 に従って改変し、[ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) と [ADR-050](ADR-050-define-backend-class-roles-and-naming.md) の `update` の手順と、競合の例外の置き場所を書き換える。
データパッチの規約（[PostgreSQL の共通カラム](../database/postgresql-common-columns.md)、[PostgreSQL のロックを抑えるスキーマ変更](../database/postgresql-online-schema-change.md)）と、トリガーを使わない規約（[PostgreSQL のテーブル以外の DB オブジェクト](../database/postgresql-database-objects.md)）は変えない。

## Consequences

### Positive

- Repository が書くのは業務の列の値だけになり、版の条件、版の設定、件数の判定、`55P03` の変換を書く場所がなくなる。
- 書き込みの入口は `insert` と `update` の二つで、Repository が調整する手順は 1 回の呼び出しになる。
  内部の主キーの読み直し、保存済みの子の読み取り、子の削除と更新と追加の振り分けは、Repository から消える。
- 親の外部キーの条件は `TableWriter` が付けるため、子の書き込みに親の条件を書き忘れて、他の集約の行を書く誤りは書けない。
- 期待する版は集約ルートのクラス（`AggregateTable` の `A`）から読むため、テーブルから読み直した版や、版を返すラムダを渡す誤りは書けない。集約ルートを作り直して渡す誤りは、型では止まらない。
- jOOQ の INSERT と UPDATE を含む書き込みの API、Spring JDBC、JDBC と、接続の元を受け取るライブラリを `TableWriter` の外で使うと、テスト（ArchUnit）で失敗する。
  禁止の一覧は、テストで固定した jOOQ の版の API で見直してある。
  別のテーブルの列と型の違う値は、コンパイルで失敗する。別の集約の子の行は、宣言した子と合わないため、SQL の前に `IllegalArgumentException` になる。
- 競合と行なしの判定は `TableWriter` の実 PostgreSQL のテストに集まり、Repository のテストは列と集約の往復と子の行の差分だけを確かめる。
- 更新と削除の Command に `VersionedCommand` の実装忘れ、`ensureLockNo` の呼び忘れ、`ExpectedLockNo` の偽造があると、ArchUnit で失敗する。
- 競合の例外が一つになり、集約ごとに例外を作らなくてよい。
  409 への対応づけも一か所で済む。
- 集約ルートの UPDATE が行ロックを取るため、同じトランザクションで後から書く子の行も、`TableWriter` の経路ではルートの行ロックで守られる。

### Negative

- 次の書き込みの誤りは、機械で検出しない。
  - **Java の外の書き込み**：psql のデータパッチと Liquibase のデータ変更は、`lock_no` を進め忘れても何も止めない。
    変えない規約ではデータパッチは `lock_no` を進めないため、パッチの前に開いた画面の保存が、パッチの結果を上書きしうる。
    これは規約が受け入れているリスクとする。
  - **jOOQ の新しい書き込みの API**：jOOQ の版を上げると、ArchUnit の禁止の一覧にない書き込みの API が増えうる。
    実行時の版を固定したテストが失敗するため、「jOOQの版を上げるとき」の手順で一覧を見直す。
  - **書き込みの検査の前提**：`TableWriter` は、接続が自動コミットではなく読み取り専用でないことでトランザクションを見分ける。
    接続 pool の既定の自動コミットを `false` にすると、トランザクションなしの書き込みが通る。この設定を変えるときは、この検査を見直す。
  - **`A` に `Versioned` を指定する誤り**：`AggregateTable` の第 3 型引数に集約ルートのクラスでなく `Versioned` を指定すると、版を返すラムダを渡せる。
    ArchUnit は型引数を検査しないため、レビューで見る。
  - **子の宣言の外部キーの列の誤り**：`ChildTable.of` は、親の外部キーの列の型が `Long` であることしか確かめない。
    `AggregateDeclarationInspectionTest` が、宣言した子の `parentKey` が親への外部キーの列に含まれることと、同じモジュールの外部キーの参照元が親の子として宣言されていることを、カタログの外部キーと突き合わせて確かめる。
    別の集約への参照は、理由を付けた許可リストに載せる（最初の項目は `tenancy.m_membership` から `tenancy.m_user`）。
    外部キーを置かない表（規約の対象外）は、突き合わせの対象にならない。
  - **集約ルートの形を外れた集約**：規則は、`domain.model` にあり `Versioned` を実装する型を集約ルートとみなす。
    Repository の `add`、`update`、`delete` の引数はこの形を検査する。
    `add` を持たずに `save` のような名前だけで保存する Repository と、Repository のインタフェースを経由せずに保存する集約は、形を外れても規則が空のまま通る。
  - **psql による古い版での物理削除**：psql で古い版の行を削除しても、何も検出しない。
    これも規約が受け入れているリスクとする。
    手順書で `WHERE lock_no = ?` を求めることは、将来の緩和策の候補にとどめる。
  - **別の集約のインスタンスへの `ensureLockNo`**：R4 は `handle` の中で `ensureLockNo(ExpectedLockNo)` を直接呼んだかしか見ない。
    保存する集約と別のインスタンスに呼んでも通る。
  - **実行されないラムダの中の呼び出し**：ArchUnit はラムダの本体の呼び出しを外側のメソッドの呼び出しとして数えるため、`Optional.ifPresent(o -> o.ensureLockNo(...))` のように実行されないことがあるラムダの中の呼び出しでも、R4 と、`TableWriter.update` の呼び出しを求める規則（H3）を満たす。
  - **`ensureLockNo` の位置と引数**：業務の検査や外部の呼び出しの後に呼ぶ書き方と、`command.expectedLockNo()` 以外の値を渡す書き方は検出しない。
    `ExpectedLockNo` を作れるのは Request だけなので、渡せる値は別の Command か別の Request の値に限られる。
  - **R1 が見ない Command の作り方**：R1 が画面から作られた Command とみなすのは、コンストラクタの呼び出しと、Command 自身が宣言する static factory の呼び出しだけである。
    `XxxCommand.builder()...build()` のような builder と、Request が呼ぶ `application` の static な helper や mapper で作る Command は検出しないため、レビューで見る。
- `VersionedCommand` と `ExpectedLockNo` が `shared.concurrency` の公開型になり、Modulith の公開型の数と、アーキテクチャ指標の全体の相対可視性（7/12 から 17/26）が上がる。
- 子の行は、保存後にあるべき全行を渡す置き換えとして書く。
  子の一部だけを渡すと、残りを削除する。宣言した子を渡さない呼び出しは `IllegalArgumentException` にするが、遅延して読んだ子の一部を渡す誤りは検出できない。
  変わっていない子の行も UPDATE して版と `updated_*` を進める。
  子の版を比べる処理はないため、進めた版に意味はない。
  メンバー数に上限のないテナントでは、役割を 1 件変えるたびに、ルートの行ロックを持ったまま、残る全メンバーの行を UPDATE する。
- 子の行の更新は、差分のために自然キーを毎回読み、1 行ずつ UPDATE する。
  子が多い集約が出たら、`ChildWriter` の中で batch にする。
- 同じトランザクションで同じ集約を二度保存すると、二度目は期待する版が古く競合になる。
  CommandHandler は集約を一度だけ保存する規約のままにする。
- `ColumnValues` の値に別のテーブルの列の式を渡すことは、型で止まらない。
  PostgreSQL が `42P01`（FROM 句にないテーブル）で拒否するため、Repository の往復のテストで失敗する。
- 追記だけのワークテーブル（`w_` の接頭辞、更新のカラムを持たない）は、`AggregateTable` と `ChildTable` が `updated_*` を求め、`insertInto` を `TableWriter` の外で呼べないため、書く入口がない。
  今は `w_` のテーブルがなく、必要になったとき、この ADR を更新して入口を足す。
- 取り込んだガイドラインの方式から外れ、出典の節に改変を記す必要がある。
- 集約ルートの業務の列が変わらない更新でも集約ルートの行を書くため、WAL が増える。
- 件数が 0 の UPDATE と主キーの確認の間に他の人が行を消すと、競合ではなく「行がない」として返る。
- ロックを取れない UPDATE は、即座に失敗せず、`lock_timeout` まで待ってから競合になる（[ADR-055](ADR-055-set-db-time-limits-per-connection.md)）。

### Neutral

- `shared` が公開する書き込みの型は、`TableWriter`、`ColumnValues`、`AggregateTable`、`ChildTable`、`ChildRows` の五つで、集約ルートの印の `Versioned` は `shared.concurrency` に置く。
- `error` は `shared.concurrency` に内側向きに依存する。
  `shared` は `error` と Domain の型に依存しない。
  `error` から `shared` への依存が増えるため、`shared` はルートパッケージの型に依存しない（[ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md)）。
- 版を比べる書き込みの Command が `VersionedCommand` を持つ規約は、R1 から R4 の限界の内側で守られる。
  限界は Negative に書いたとおりである。

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

### 選択肢7: 共通処理が error モジュールの例外を投げる

- **Description**：`TableWriter` が `error` モジュールの競合と未検出の例外を投げる。
- **Pros**：呼び出し側が例外を作る関数を渡さずに済む。
- **Cons**：`shared` が `error` に依存し、依存の向きが内側へ向かなくなる。
  例外を `shared.concurrency` に置けば、`error` が内側の `shared` に依存する向きのまま、関数の受け渡しもなくなるため、これを採る。

### 選択肢8: DB のトリガーで版の前進を強制する

- **Description**：業務テーブルに BEFORE UPDATE の行トリガーを付け、`lock_no` を 1 進めない UPDATE を拒否する。
- **Pros**：psql のデータパッチ、Liquibase のデータ変更、別のプロセスの書き込みにも効く。
- **Cons**：「トリガーは使わない」の規約（[PostgreSQL のテーブル以外の DB オブジェクト](../database/postgresql-database-objects.md)）を保つという利用者の決定により採らない。
  Java の外の書き込みの誤りは、Negative に書いた残るリスクとして扱う。

### 選択肢9: 集約ごとの ConflictException を関数で渡す

- **Description**：この ADR の前の版で、`<Aggregate>ConflictException` を `domain.model` に置き、`OrderConflictException::new` のような関数を `TableWriter` に渡す。
- **Pros**：例外が集約の語彙になり、`shared` が Domain に依存しない。
- **Cons**：集約ごとの例外と 409 への対応づけが要り、関数の渡し忘れと例外の作り忘れが残る。
  集約ごとに例外を作る手間が、集約の追加のたびに生じる。

### 選択肢10: IllegalStateException を 409 にする

- **Description**：競合に `IllegalStateException` を使い、`ApiExceptionHandler` で 409 にする。
- **Pros**：例外を新しく作らずに済む。
- **Cons**：`IllegalStateException` は許されない状態遷移にも使うため、許されない状態遷移（ADR-062 では 422 の `BusinessRuleViolationException`）まで 409 にしてしまう。

### 選択肢11: Command の long lockNo を名前の規則で検査する

- **Description**：Command に `long lockNo` を持たせ、ArchUnit が `lockNo()` の名前で検査する。
- **Pros**：型を増やさずに済む。
- **Cons**：名前で判定するため、名前を変えた Command と `ensureLockNo` に読んだ版を渡す書き方が通る。
  `ExpectedLockNo` の型なら、生成を Request に限る規則（R3）で、読んだ版を渡す偽造を止められる。

### 選択肢12: ルートの更新と子の書き込みを手順として呼ぶ入口

- **Description**：この ADR の前の版で、`updateCheckingVersion` がルートを更新して `LockedRoot` を返し、Repository が `updateChild`、`deleteChildren` と自分の INSERT で子を書く。
  期待する版は `long` の引数で渡し、版を比べない書き込みは `updateWhere` と `deleteWhere` で行う。
- **Pros**：子の書き込みの順序と範囲を Repository が自由に決められる。
- **Cons**：Repository が手順を 5 つ並べ、子の条件に親の外部キーを自分で書く。書き忘れると他の集約の行を書き、ArchUnit では検出できない。
  INSERT が別の入口（`Insert` を受け取る）で、件数を捨てる、ignore-on-conflict の形を使える。
  版の引数に、テーブルから読み直した版を渡せる。
  呼び出しのない `updateWhere` と `deleteWhere` が、版を比べずに書ける抜け道として残り、それを守る規則（H5）、`@CheckReturnValue` の検査、INSERT の規則（H7）が要った。

### 選択肢13: 集約全体を写像する汎用の保存

- **Description**：集約の値から列への変換を、宣言に登録する（`Function<Aggregate, Row>` やリフレクション）。
- **Pros**：Repository が `save(aggregate)` の一行になる。
- **Cons**：宣言が小さな ORM になり、並び順、論理削除、入れ子の子と、宣言が増え続ける。
  値の変換は呼び出しごとのラムダに置き、宣言は構造と `keyOf` だけにする。

## References

- [ADR-040: Future のアーキテクチャ設計ガイドラインを書き直して docs に取り込む](ADR-040-import-future-architecture-guidelines.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [可観測性の規約](../observability/conventions.md)
- [HTTP ステータスコード](../web-api/status-codes.md)
- [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107)
- [ADR-062: 業務上の失敗を 3 つの例外の型で表し、404、409、422 の Problem Details に対応づける](ADR-062-map-business-exceptions-to-404-409-422.md)
- [ADR-055: DB のロック待ち、文の実行、トランザクション中の待機の上限を接続ごとに設定する](ADR-055-set-db-time-limits-per-connection.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [jOOQ の SQL の書き方](../database/jooq-usage.md)
- [クラスの役割：jOOQ の Repository](../backend/class-roles/jooq-repository.md)
- [バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)
- [PostgreSQL 18, Read Committed Isolation Level](https://www.postgresql.org/docs/18/transaction-iso.html#XACT-READ-COMMITTED)
- [PostgreSQL 18, Explicit Locking](https://www.postgresql.org/docs/18/explicit-locking.html)
- [jOOQ, Optimistic locking](https://www.jooq.org/doc/latest/manual/sql-execution/crud-with-updatablerecords/optimistic-locking/)
