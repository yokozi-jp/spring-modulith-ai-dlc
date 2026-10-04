---
type: Convention
title: jOOQのSQLの書き方
description: jOOQでSQLを組み立てるときに使わないAPIと設定、業務テーブルの書き込みの入口、jOOQの版を上げるときの見直しの手順を定める規約。infrastructure.persistenceでjOOQのクエリを書くとき、jOOQのSettingsを変えたくなったとき、jOOQかSpring Bootの版を上げるときに読む。
tags: [convention, database, jooq]
---

# jOOQのSQLの書き方

SQLは生成されたメタモデルのクラスから組み立てる。
Plain SQLのAPIと`withRenderSchema(false)`は使わない。
業務テーブルのUPDATEとDELETEは、sharedモジュールの`TableWriter`で書く。

## 対象

この規約は、`..infrastructure.persistence..`でjOOQを使う手書きのコードに適用する。
jOOQの生成コードには適用しない。

## Plain SQL

`@PlainSQL`の付いたメソッドを使わない。
たとえば`DSL.sql(String)`、`DSL.field(String)`、`DSL.condition(String)`、`DSLContext.fetch(String)`、`DSLContext.execute(String)`、`DSLContext.resultQuery(String)`が該当する。
テーブルとカラムは生成されたクラスから参照する。

文字列のSQLは、jOOQを採用した理由であるスキーマとのコンパイル時の照合を失う（[ADR-003](../adr/ADR-003-adopt-jooq-for-data-access.md)）。

## スキーマ名の出力

`Settings.withRenderSchema(false)`を使わず、SQLをスキーマ名で修飾したままにする。
`search_path`による暗黙の振り分けは[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)が禁止している。

## 楽観的ロック

jOOQの楽観的ロックの機能を使わない規則は、[PostgreSQLの排他制御](postgresql-concurrency-control.md)に従う。
業務テーブルのUPDATEとDELETEは、sharedモジュールの`TableWriter`だけが組み立てて実行する（[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
`TableWriter`、`LockedRoot`、`DeletedRoot`の外で使わない書き込みのAPIの一覧は、[アーキテクチャテスト](../backend/architecture-tests.md)の「書き込みの入口」にある。

## 共通処理

INSERTの共通カラムの値は、sharedモジュールの`CommonColumns.forInsert(table)`が返し、`set(...)`で登録する（[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）。
UPDATEの共通カラムの値と`lock_no`は`TableWriter`が書く。
`CommonColumns.forUpdate`は`TableWriter`だけが使うpackage-privateのメソッドである。
Repositoryでの書き方は[クラスの役割：jOOQ の Repository](../backend/class-roles/jooq-repository.md)に従う。

NOT NULLの`varchar`には、コード生成がNULLを空文字へそろえるConverterを当てる。
文字列の共通カラムと`patched_*`には当てない（[PostgreSQLのデータ型](postgresql-data-types.md)）。
このConverterは外部結合（LEFT JOIN）で生じたNULLも空文字に変えるため、結合先の行があるかどうかは文字列のカラムではなく主キーで判定する。

## 検査

Plain SQLのAPIと`withRenderSchema(false)`の使用は、ArchUnitの`DatabaseConventionsArchTest`で検査する。
`TableWriter`の外の書き込みと入口の選び分けは、ArchUnitの`TableWriterArchTest`で検査する（[バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)）。

## jOOQの版を上げるとき

jOOQの版を上げると、`TableWriterArchTest.tableWritesGoThroughTableWriter`の禁止の一覧にない書き込みのAPIが増えうる。
そのため、実行時のjOOQの版を`TableWriterArchTest.jooqVersionIsReviewed`で固定している。

Spring Bootの版、`backend/build.gradle`のjOOQのコード生成のプラグインの版を変える人と、それらを変えるDependabotのPull Requestをマージする人は、次の手順で見直す。

1. 実行時のjOOQの版が変われば、`jooqVersionIsReviewed`が失敗し、新しい版を示す。
   コード生成のプラグインの版だけを変えたときは失敗しないため、2から4の手順で見直す。
2. jOOQのリリースノートと、`javap`で`DSLContext`、`DSL`、`WithStep`、`Update*`、`Delete*`、`Merge*`、`Insert*Step`、`InsertQuery`、`Loader*Step`、`UpdatableRecord`、`DAO`、`QOM`の公開メソッドを確かめ、UPDATE、DELETE、UPSERT、MERGEを作るか実行する新しいAPIを探す。
   `Update`か`Delete`を戻り値の型に持つメソッドは、作る入口である。
3. 新しい書き込みの入口があれば、`tableWritesGoThroughTableWriter`の禁止の一覧、違反のフィクスチャ`DirectOrderWriter`、[アーキテクチャテスト](../backend/architecture-tests.md)の「書き込みの入口」に足す。
4. コード生成のプラグインと実行時のjOOQのマイナー版をそろえ、[jOOQコード生成物の管理](jooq-codegen.md)に従って生成し直す。
5. `TableWriterArchTest`の`REVIEWED_JOOQ_VERSION`を新しい版に更新する。

実行時の版とコード生成の版の関係は、`backend/gradle/database.gradle`の`jooq`ブロックのコメントに書いている。

## 参照資料

- jOOQ Plain SQL: <https://www.jooq.org/doc/latest/manual/sql-building/plain-sql/>
