---
type: Convention
title: PostgreSQLの排他制御
description: トランザクション分離レベル、楽観的ロック、悲観的ロック、DBの行ロックの使い分けと、業務テーブルのUPDATEとDELETEをTableWriterで書く規則、ロック待ちとデッドロックの扱いを定める規約。業務テーブルのUPDATEかDELETEを書くとき（集約の保存と削除、在庫の引き当て、保存期間を過ぎた行の削除、ワークテーブルの後始末）、分離レベルを変えたくなったときに読む。
tags: [convention, database, postgresql, concurrency, locking, future-arch-guidelines]
---

# PostgreSQLの排他制御

分離レベルはPostgreSQLの既定のREAD COMMITTEDのまま使う。
同じ行を同時に更新しうる処理には楽観的ロックを使い、UPDATEの条件で`lock_no`を比較して更新件数で判定する。
業務テーブルのUPDATEとDELETEは、sharedモジュールの`TableWriter`だけで書く。
画面を開いた時点で行をロックする悲観的ロックは避ける。

## 分離レベル

分離レベルはREAD COMMITTEDにし、`@Transactional`の`isolation`を指定しない。
業務処理に必要な整合性は、UPDATEと`SELECT ... FOR UPDATE`が取る行ロックで守る。
PostgreSQLではREPEATABLE READ以上にすると、直列化の失敗によるエラーが積極的に起きるためである。

次のどちらかに当てはまる場合に限り、REPEATABLE READを使ってよい。

- WHERE句のサブクエリが、更新対象のテーブルを自己参照する。
- 複雑なレポートの作成など、トランザクションの中で数値の計算の整合性を保つ必要がある。

## ロック方式の選び方

複数の利用者や処理が同じテーブルの行を同時に変更しうる場合は、ロックを取り、他の誰かの変更を誤って上書きしないようにする。

- **楽観的ロック**：変更前にロックを取らず、更新時にバージョンを比較して競合を検知する。競合したら利用者に入力し直してもらう。
- **悲観的ロック**：変更前にロックを取り、他の処理が対象の行を変更できないようにする。
- **DBの行ロック**：UPDATE文の条件と更新件数で、業務上正しく更新できたかを判定する。

楽観的ロックを優先して使う。
悲観的ロックの画面設計は、入力中のデータを破棄することが業務上どうしても許容できない場合を除き避ける。
DBの行ロックは、在庫の引き当てのように性能が重要になる処理でだけ使う。

## 楽観的ロック

ロック番号には[共通カラム](postgresql-common-columns.md)の`lock_no`を使い、最終更新日時で代用しない。
業務テーブルのUPDATEとDELETEは、sharedモジュールの`TableWriter`だけが組み立てて実行する（[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
版の条件、`lock_no`の設定、更新件数の判定、ロック待ちの失敗の変換は`TableWriter`が持ち、Repositoryは業務の列の値だけを書く。
Repositoryでの書き方は[jOOQのRepository](../backend/class-roles/jooq-repository.md)に従う。

入口は、呼び出し側が期待する版（以前に読んだ集約ルートの`lock_no`）を持っているかだけで選ぶ。

- **期待する版を持つ**：`updateCheckingVersion`と`deleteCheckingVersion`を使う。
  更新件数が1件なら成功する。
  0件なら主キーで行の有無を確かめ、行がなければ`shared.failure`の`NotFoundException`を、行があれば「他の人が更新した」として`shared.concurrency`の`ConflictException`を投げる。
  子の行は、戻り値の`LockedRoot`（削除では`DeletedRoot`）で書く。
  `updateChild`は、子の行が0件なら`shared.failure`の`BusinessRuleViolationException`（422）を、2件以上なら`IllegalStateException`を投げる（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
- **期待する版を持たない**：`updateWhere`と`deleteWhere`を使う。
  更新件数を返し、件数の意味は呼び出し側が決める。

`TableWriter`のどのUPDATEも`lock_no`を1進める。
`updateCheckingVersion`は、期待する版が6の行に対して次のSQLを実行する。

```sql
UPDATE t_order SET status = 'PAID', updated_at = ..., updated_by = ..., updated_pgm_cd = ..., updated_tx_id = ..., lock_no = 7
WHERE order_id = 1 AND lock_no = 6;
```

`updateWhere`は、`lock_no = lock_no + 1`で版を進める。
版を比べない更新が先に走れば、その前に読んだ画面の保存は上書きせずに競合として返る。

### 対象と対象外の分け方

`lock_no`は全業務テーブルにあるため、列の有無では対象を分けない。
版を比べるかは、期待する版を持っているかで決まる。
機械の検査の列は、書き間違いを検出する検査である。

| 書き込みの種類                                                                              | 版を比べるか                                   | 使うAPI                                                                                                                                                         | 機械の検査                                                                                                                                                                                                                                                                                                       |
| ------------------------------------------------------------------------------------------- | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 画面からの集約の更新（Commandが`VersionedCommand`）                                         | 比べる（画面の版と読んだ版、読んだ版と行の版） | Requestが作る`ExpectedLockNo`、CommandHandlerの`ensureLockNo`と、Repositoryの`update`の`updateCheckingVersion`                                                  | `commandsBuiltByPresentationForWritesAreVersioned`、`commandHandlersEnsureScreenLockNo`、`expectedLockNoIsCreatedOnlyByRequests`、`onlyCommandHandlersUpdateOrDeleteAggregates`、`repositoryUpdateAndDeleteCheckVersion`、`repositoryWritesTakeVersionedAggregates`、`aggregateMethodsDoNotUseUnversionedWrites` |
| Listenerやバッチからの集約の更新（画面の版を持たない）                                      | 比べる（読んだ版と行の版）                     | Repositoryの`update`の`updateCheckingVersion`                                                                                                                   | `repositoryUpdateAndDeleteCheckVersion`、`repositoryWritesTakeVersionedAggregates`、`aggregateMethodsDoNotUseUnversionedWrites`                                                                                                                                                                                  |
| 集約ルートの物理削除                                                                        | 比べる                                         | Repositoryの`delete`の`deleteCheckingVersion`                                                                                                                   | `repositoryUpdateAndDeleteCheckVersion`、`repositoryWritesTakeVersionedAggregates`、`aggregateMethodsDoNotUseUnversionedWrites`                                                                                                                                                                                  |
| 集約の子の行の更新                                                                          | 比べない（ルートの行ロックと版で守る）         | `LockedRoot.updateChild`                                                                                                                                        | `tableWritesGoThroughTableWriter`                                                                                                                                                                                                                                                                                |
| 集約の子の行の削除（子の集合が減ったとき、ルートを削除したとき）                            | 比べない                                       | `LockedRoot.deleteChildren`、`DeletedRoot.deleteChildren`                                                                                                       | `tableWritesGoThroughTableWriter`                                                                                                                                                                                                                                                                                |
| 集約の子の行の追加                                                                          | 該当なし（`lock_no = 1`）                      | `insertInto(...).set(commonColumns.forInsert(...))`。`LockedRoot`を通らない                                                                                     | 既定値のないNOT NULL。ルートを書かない追加は検出しない（ADR-054のNegative）                                                                                                                                                                                                                                      |
| DBの行ロック（在庫の引き当て）、外部同期による状態の上書き、一括更新                        | 比べない                                       | `updateWhere`（件数を返す）                                                                                                                                     | `tableWritesGoThroughTableWriter`、`aggregateMethodsDoNotUseUnversionedWrites`、Error Proneの`CheckReturnValue`                                                                                                                                                                                                  |
| ワークテーブルの後始末、保存期間を過ぎた行の削除、集約を読み込まない一括の物理削除          | 比べない                                       | `deleteWhere`（件数を返す）                                                                                                                                     | `tableWritesGoThroughTableWriter`、`aggregateMethodsDoNotUseUnversionedWrites`、Error Proneの`CheckReturnValue`                                                                                                                                                                                                  |
| 集約ルートのINSERT                                                                          | 該当なし（`lock_no = 1`）                      | `insertInto(...).set(commonColumns.forInsert(...))`                                                                                                             | 既定値のないNOT NULL                                                                                                                                                                                                                                                                                             |
| 追記だけのワークテーブル（`w_`）                                                            | 比べない                                       | 追記だけにする。INSERTは`forInsert`、削除は`deleteWhere`                                                                                                        | `tableWritesGoThroughTableWriter`                                                                                                                                                                                                                                                                                |
| Javaの外の書き込み（psqlのデータパッチ、Liquibaseのデータ変更、スキーマ変更に伴う値の設定） | 比べない                                       | [共通カラム](postgresql-common-columns.md)（データパッチでは`patched_*`だけを更新する）と[ロックを抑えるスキーマ変更](postgresql-online-schema-change.md)に従う | なし（ADR-054のNegative）                                                                                                                                                                                                                                                                                        |
| フレームワークのテーブル（`modulith`と`liquibase`のスキーマ）                               | 対象外                                         | 対象外                                                                                                                                                          | スキーマ検査の対象外（`SchemaConventions.FRAMEWORK_SCHEMAS`）                                                                                                                                                                                                                                                    |

期待する版を持つ書き込みは、集約ルートを受け取るRepositoryの`update`と`delete`に限る。
識別子と版を受け取るRepositoryのメソッドを作らない。

### 親子のテーブルの保存の順序

集約のように親子のテーブルを一緒に書くときは、先に親の行（集約のルート）を`updateCheckingVersion`で更新し、子の行は後で書く。
親の業務のカラムが変わらなくても、親の行を更新して`lock_no`を進める。

子の集合が増減する集約は、全置換ではなく差分で保存する。
全置換では、子の`created_*`が保存のたびに変わる。

1. 親を`updateCheckingVersion`で更新する。
2. 保存済みの子のキーを読む。
3. 集約にない子を`root.deleteChildren(子のテーブル, 親の条件.and(キー.notIn(集約の子のキー)))`で削除する。
   集約の子が空なら`notIn`は常に真になり、すべての子を削除する。
4. 保存済みにない子を`forInsert`でINSERTする。
5. 両方にある子を`root.updateChild`で更新する。

### jOOQの楽観的ロックの機能

jOOQの`Settings`の`executeWithOptimisticLocking`と、コード生成の`recordVersionFields`を使わない。
これらは`UpdatableRecord.store()`で更新するときにしか働かず、このリポジトリはUPDATEを`TableWriter`のDSLで書くためである。

jOOQの機能の動作は[jOOQのマニュアル](https://www.jooq.org/doc/latest/manual/sql-execution/crud-with-updatablerecords/optimistic-locking/)を参照する。

## デッドロックの防止

`SELECT ... FOR UPDATE`で複数の行やテーブルをロックする場合は、ロックするテーブルの順序と、行の並び順（主キーの順など）を決め、すべての処理がそれに従う。
UPDATEも更新する行のロックを取るため、同じ順序に従う。
楽観的ロックでは、親の行を先に更新し、子の行は主キーの順に更新する。

## ロック待ち

アプリの接続には`lock_timeout`でロック待ちの上限を設定し、既定の無期限待ちのままにしない（[DB接続情報とロール分離](connections.md)、[ADR-055](../adr/ADR-055-set-db-time-limits-per-connection.md)）。
上限まで待ってもロックを取れなければ、文はSQLSTATE `55P03`で失敗する。
`updateCheckingVersion`、`deleteCheckingVersion`と、子の行の`LockedRoot.updateChild`、`LockedRoot.deleteChildren`、`DeletedRoot.deleteChildren`は、この失敗を、原因を付けずに種類`lock`（`ConflictException.Kind.LOCK`）の`ConflictException`に変える。
`updateWhere`と`deleteWhere`は、Springの`CannotAcquireLockException`と、`updateWhere`の`DuplicateKeyException`をそのまま投げ、扱いは呼び出し側が決める。
一意制約の違反（SQLSTATE `23505`）は、`TableWriter`の版を比べる入口、子の行の書き込み、集約ルートのINSERTの入口`insert`で、`DuplicateKeyException`を、原因を付けずに種類`unique`（`ConflictException.Kind.UNIQUE`）の`ConflictException`に変える。
原因の例外のメッセージにはSQLと重複したキーの値が入るため、原因を付けない（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
集約ルートのINSERTは`TableWriter.insert`で実行し、子の行の`dsl.batch`のINSERTは変換しない。

要件に合わせて、次の待ち方も使う。

- **`NOWAIT`**：DBの行ロックやバッチで`SELECT ... FOR UPDATE`に付け、ロックを取れなければ即座にエラーにする。
- **`SKIP LOCKED`**：バッチで`SELECT ... FOR UPDATE`に付け、ロックを取れない行を飛ばして残りの行をロックする。
- **`SET LOCAL lock_timeout`**：1つのトランザクションだけ待ち時間の上限を変える。

## 悲観的ロック

HTTPの要求と応答をまたいでDBのトランザクションを保てないため、一覧を表示した時点で`SELECT ... FOR UPDATE`によりロックを取る方式は実現できない。
画面を開いた時点でロックする要件が出たら、まず要件を調整する。
どうしても必要な場合は、ロック用のテーブルを作ってアプリケーションでロックを再現し、ロックを解放するタイミングを同時に設計する。

## DBの行ロック

数量の更新のように、前回の値を確認する必要がない処理では、`updateWhere`の条件付きのUPDATEと更新件数で判定してよい。

```java
final int reserved =
    tableWriter.updateWhere(
        T_STOCK,
        T_STOCK.ITEM_ID.eq(itemId).and(T_STOCK.STOCK_COUNT.ge(5)),
        set -> set.set(T_STOCK.STOCK_COUNT, T_STOCK.STOCK_COUNT.minus(5)));
```

このUPDATEも`lock_no = lock_no + 1`で版を進めるため、引き当ての前に読んだ画面の保存は競合になる。
更新件数が0件なら、在庫不足として利用者へ返し、業務判断を委ねる。
マスタの保守のように後勝ちの上書きを許容できない更新と、状態の遷移を伴う更新には使わない。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 楽観的ロックの方式を、先にロックしてから比較する原典の方式から、UPDATEの条件と更新件数で判定し、業務テーブルのUPDATEとDELETEを`TableWriter`に集める方式に変えている（[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
  jOOQの楽観的ロックの機能を使わない規則を追加している。
- ロック待ちの上限を、アプリの接続ごとに`lock_timeout`で設定する規則を追加している（[ADR-055](../adr/ADR-055-set-db-time-limits-per-connection.md)）。
