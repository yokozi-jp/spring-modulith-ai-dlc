---
type: Reference
title: DB操作コマンドのリファレンス
description: DBを操作するTaskとGradleタスクの動作、影響範囲、必要な確認値を示し、実行するDBコマンドを選ぶときに読むリファレンス。
tags: [reference, database, liquibase, jooq, taskfile, gradle]
---

# DB操作コマンドのリファレンス

`-check`と`-preview`はDBを変更せず、`be-migrate`系は前進適用する。
`be-verify-migrations`、`task test`、`task test-dev`は使い捨てDBだけを使う。
`be-rollback`は`CONFIRM_ROLLBACK=yes`がなければDBへ接続しない。

## Task

- **`task be-schema-tag-check`**：現在のスキーマタグがDBにあるか照会する。
  DBの読み取りだけを行う。
- **`task be-rollback-check DB_ROLLBACK_TAG=<tag>`**：切り戻し対象タグの存在を確認する。
  DBの読み取りだけを行う。
- **`task be-rollback-preview DB_ROLLBACK_TAG=<tag>`**：切り戻しSQLを`build/reports/liquibase/rollback-preview.sql`へ生成する。
  DBを変更せず、ファイルを生成する。
- **`task be-generate-jooq`**：現在のDBスキーマからjOOQコードを生成する。
  DBを読み取り、生成ソースを更新する。
- **`task be-migrate`**：現在のスキーマタグまでchangesetを前進適用し、タグを確認する。
  対象DBへchangesetを適用する。
- **`task be-migrate-dev`**：作りかけを含む全changesetを前進適用する。
  開発DBへchangesetを適用する。
- **`task be-refresh-jooq`**：現在のスキーマタグまで前進適用してjOOQコードを生成する。
  対象DBへchangesetを適用し、生成ソースを更新する。
- **`task be-verify-migrations`**：changesetの適用、rollback、再適用、現在タグを検証する。
  使い捨てDBだけを変更する。
- **`task test`**：隔離スタックで確定済みマイグレーションを検証してからバックエンドテストを実行する。
  使い捨てDBだけを変更し、終了時に削除する。
- **`task test-dev`**：隔離スタックで作りかけを含むchangesetを適用してバックエンドテストを実行する。
  使い捨てDBだけを変更し、終了時に削除する。
- **`task be-release-migrate`**：リポジトリの現在タグまで本番向けに前進適用する。
  `MIGRATION_DB_*`が示すDBへchangesetを適用する。
- **`task be-rollback DB_ROLLBACK_TAG=<tag> CONFIRM_ROLLBACK=yes`**：指定タグより後のchangesetを切り戻す。
  対象DBのスキーマまたはデータを失う可能性がある。

場面ごとのローカル実行順は[開発ワークフロー](../dev-workflow.md)を参照する。
本番の前進適用と切り戻し手順は[DBのデプロイと切り戻し](runbook-deploy-and-rollback.md)を参照する。
生成コードの扱いは[jOOQコード生成物の管理](jooq-codegen.md)を参照する。

## Gradleタスク

`backend`ディレクトリでは次のタスクを直接実行できる。

- **`./gradlew migrateDatabase`**：`databaseSchemaTag`までchangesetを前進適用し、タグを確認する。
- **`./gradlew update`**：作りかけを含む全changesetを前進適用する。
- **`./gradlew checkCurrentSchemaTag`**：現在のスキーマタグが対象DBにあるか確認する。
- **`./gradlew verifyDatabaseMigrations`**：使い捨てDBで全changesetの適用、rollback、再適用、現在タグを検証する。
- **`./gradlew jooqCodegen`**：現在のDBスキーマを読み取り、コード生成だけを実行する。
- **`./gradlew migrateAndGenerateJooq`**：現在のスキーマタグまで前進適用してからjOOQコードを生成する。

`verifyDatabaseMigrations`は使い捨てDBだけで実行する。
Liquibaseプラグインの`update`は現在タグより後のchangesetも適用するため、本番マイグレーションの入口に使わない。
