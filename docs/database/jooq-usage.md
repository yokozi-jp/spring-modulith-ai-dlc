---
type: Convention
title: jOOQのSQLの書き方
description: jOOQでSQLを組み立てるときに使わないAPIと設定を定める規約。infrastructure.persistenceでjOOQのクエリを書くとき、jOOQのSettingsを変えたくなったときに読む。
tags: [convention, database, jooq]
---

# jOOQのSQLの書き方

SQLは生成されたメタモデルのクラスから組み立てる。
Plain SQLのAPIと`withRenderSchema(false)`は使わない。

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

## 共通処理

共通カラムと楽観的ロックは、sharedモジュールの共通処理を使って実装する（[ADR-046](../adr/ADR-046-add-shared-module-for-jooq-common-code.md)）。

- **INSERT**：`CommonColumns.forInsert(table, UseCase.class)`が返す共通カラムの値を、`set(...)`で登録する。
- **UPDATE**：他の処理も更新しうる行は、`OptimisticLock.update(...)`で更新する。`SELECT ... FOR UPDATE NOWAIT`で行をロックしてから`lock_no`を比べ、一致すれば`lock_no`を1加算する。
- **競合と未検出**：`lock_no`の不一致とロックの取得の失敗は409、行がない場合は404の応答になる。

NOT NULLの`varchar`には、コード生成がNULLを空文字へそろえるConverterを当てる。
文字列の共通カラムと`patched_*`には当てない（[PostgreSQLのデータ型](postgresql-data-types.md)）。
このConverterは外部結合（LEFT JOIN）で生じたNULLも空文字に変えるため、結合先の行があるかどうかは文字列のカラムではなく主キーで判定する。

## 検査

Plain SQLのAPIと`withRenderSchema(false)`の使用は、ArchUnitで検査する。

## 参照資料

- jOOQ Plain SQL: <https://www.jooq.org/doc/latest/manual/sql-building/plain-sql/>
