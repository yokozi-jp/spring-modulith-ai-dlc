---
type: Convention
title: バッチの JobRepository
description: 業務のバッチの JobRepository の種類、メタデータの表を置くスキーマと changeset、表の権限、実行の記録の読み方、メタデータの表の時刻とタイムゾーンを定める規約で、Spring Batch のメタデータの表を作るときや権限を変えるとき、Job の実行の記録を読むときに読む。
tags: [convention, batch, spring-batch, database]
---

# バッチの JobRepository

JobRepository は Spring Boot が構成する JDBC の JobRepository にし、メタデータの表を `modulith` スキーマに Liquibase の changeset で作る。
表の権限はアプリロールにだけ与え、ジョブの実行の記録は自分で作らずに JobRepository の表から読む。
メタデータの表の時刻は JVM の既定のタイムゾーンの時刻なので、業務の判断に使わない。
決定の理由は [ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md) に示す。

## JobRepository の種類

- JobRepository は Spring Boot が構成する JDBC の JobRepository にする。
- `ResourcelessJobRepository` を使わない。

## メタデータの表

- メタデータの表は `modulith` スキーマに置き、`spring.batch.jdbc.table-prefix` を `modulith.BATCH_` にする。
- メタデータの表は、Spring Batch の `schema-postgresql.sql` と同じ形を Liquibase の changeset で作り、`spring.batch.jdbc.initialize-schema` を `never` にする。
  changeset の書き方は [DBマイグレーション規約](../database/migrations.md) に従う。
- メタデータの表と sequence の SELECT、INSERT、UPDATE、DELETE と sequence の USAGE は、アプリロールにだけ与える。
  モジュールのロールには与えない。

## 実行の記録

- ジョブの実行の記録の表を自分で作らない。
- 開始、終了、件数、成否は JobRepository の表から読む。

## 時刻とタイムゾーン

- メタデータの表の時刻の列は、JVM の既定のタイムゾーンの時刻である。
  Job を動かす JVM は、コンテナの外でも `-Duser.timezone=UTC` にする（[日時とタイムゾーンの規約](../datetime/timezone-conventions.md#バックエンド)）。
- テストの JVM は UTC でないので（同じ規約の[テスト](../datetime/timezone-conventions.md#テスト)）、テストでメタデータの時刻を UTC として比べない。
- メタデータの表の時刻を業務の判断に使わない。
  業務の期間は JobParameters で持つ。
