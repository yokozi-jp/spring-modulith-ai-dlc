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
楽観的ロックのUPDATEの更新件数は、sharedモジュールの`OptimisticLock.requireUpdated`で判定する（[ADR-052](../adr/ADR-052-detect-optimistic-lock-conflicts-by-update-count.md)）。

## 共通処理

共通カラムの値は、sharedモジュールの`CommonColumns`の`forInsert(table)`と`forUpdate(table)`が返し、`set(...)`で登録する（[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）。
Repositoryでの書き方は[クラスの役割：jOOQ の Repository](../backend/class-roles/jooq-repository.md)に従う。

NOT NULLの`varchar`には、コード生成がNULLを空文字へそろえるConverterを当てる。
文字列の共通カラムと`patched_*`には当てない（[PostgreSQLのデータ型](postgresql-data-types.md)）。
このConverterは外部結合（LEFT JOIN）で生じたNULLも空文字に変えるため、結合先の行があるかどうかは文字列のカラムではなく主キーで判定する。

## 検査

Plain SQLのAPIと`withRenderSchema(false)`の使用は、ArchUnitで検査する。

## 参照資料

- jOOQ Plain SQL: <https://www.jooq.org/doc/latest/manual/sql-building/plain-sql/>
