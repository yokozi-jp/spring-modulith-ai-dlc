---
type: Reference
title: 'DB操作コマンドの早見表'
description: DBを操作するTaskfileとGradleのタスクごとに、DBへの影響範囲と使うタイミングを示す。どのコマンドを叩くか迷ったとき、ローカルでスキーマを適用しjOOQを再生成するときに読む。
tags: [reference, database, liquibase, jooq, taskfile, gradle]
---

# DB操作コマンドの早見表

`-check`と`-preview`は安全で、`be-migrate`系は前進適用だけを行います。
`be-verify-migrations`と`task test`は使い捨てDB専用です。
破壊的なのは`be-rollback`だけで、`CONFIRM_ROLLBACK=yes`がなければDBへ接続しません。

## コマンドの影響範囲（クイックリファレンス）

どのコマンドを、どういうときに叩けばよいか迷ったときの早見表です。
判断の軸は「そのコマンドがDBに何をするか（影響範囲）」です。

`-check`と`-preview`は必ず安全です。
`be-migrate`系は前へ進めるだけで、既存の状態を戻しません。
`be-verify-migrations`と`task test`は使い捨てDB専用で、開発DBや本番DBには触れません。
破壊的なのは`be-rollback`だけで、それも`CONFIRM_ROLLBACK=yes`がなければDBへ接続せず失敗します。

| コマンド                                                      | 何をするか                                                          | 影響範囲                         | 使うタイミング                          |
| ------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------- | --------------------------------------- |
| `task be-schema-tag-check`                                    | 現在のスキーマタグがDBにあるか照会                                  | 読み取りのみ、安全               | 対象DBの状態を確認したいとき            |
| `task be-rollback-check DB_ROLLBACK_TAG=<tag>`                | 切り戻し対象タグの存在を確認                                        | 読み取りのみ、安全               | 切り戻し前の下調べ                      |
| `task be-rollback-preview DB_ROLLBACK_TAG=<tag>`              | 切り戻しSQLを生成（`build/reports/liquibase/rollback-preview.sql`） | DB変更なし、安全                 | 切り戻しの内容を実行前に確認            |
| `task be-generate-jooq`                                       | 現在のDBスキーマからjOOQコードを生成                                | DB変更なし（生成ソースを更新）   | スキーマは変えずにコードだけ再生成      |
| `task be-migrate`                                             | 現在のスキーマタグまで前進適用し、タグを確認                        | 追記のみ（前進）                 | 初回起動前、changeset追加後（ローカル） |
| `task be-refresh-jooq`                                        | 前進適用してからjOOQコードを生成                                    | 追記のみ（前進）                 | changeset追加後にまとめて実行           |
| `task be-verify-migrations`                                   | 使い捨てDBで適用、rollback、再適用、タグ確認                        | 隔離DB専用、安全                 | changeset追加後の検証、CI               |
| `task test`                                                   | 隔離スタックで上記検証を通してからテスト                            | 隔離DB専用、安全                 | 変更のローカル総合確認                  |
| `task be-release-migrate`                                     | 本番の前進適用（`MIGRATION_DB_*`必須）                              | 追記のみ（前進、本番）           | デプロイパイプラインから                |
| `task be-rollback DB_ROLLBACK_TAG=<tag> CONFIRM_ROLLBACK=yes` | 指定タグより後のchangesetを切り戻す                                 | **破壊的（データ損失の可能性）** | preview、バックアップ、影響確認の後だけ |

「安全」は、対象DBのスキーマとデータを変えないことを指します（`be-generate-jooq`はリポジトリの生成ソースを書き換えます）。
「前進」は、未適用のchangesetを新しく適用するだけで、適用済みの変更は戻さないことを指します。

各Taskfileのタスクの本体は、`backend`ディレクトリのGradleタスクを呼び出します。
`task help`で各タスクの一行説明を一覧表示できます。
本番の前進適用と切り戻しの手順は[DBのデプロイと切り戻し](runbook-deploy-and-rollback.md)にあります。

## ローカル開発

PostgreSQLを起動し、ルートの`.env`に接続情報を設定してから現在のスキーマタグまで適用します。

```bash
task compose-up
task be-migrate
```

`be-migrate`は`updateToTag`と`assertSchemaTagExists`を順序実行します。
changelogに現在タグより後のchangesetが存在しても、自動的には適用しません。

changesetを追加した後は、使い捨てDBでrollback可能性を検証します。

```bash
task test
```

`task test`は隔離したPostgreSQLを起動し、全changesetの適用、rollback、再適用、現在タグの存在確認を実行してからバックエンドテストを開始します。
終了後はテスト用ボリュームを削除します。

マイグレーションを適用して最新スキーマのjOOQソースを生成する場合は、次のコマンドを使います。

```bash
task be-refresh-jooq
```

現在のDBを変更せず、コード生成だけを再実行する場合は次のコマンドを使います。

```bash
task be-generate-jooq
```

生成先は`backend/src/generated/jooq`です。
生成コードはchangesetと同じ変更に含め、リリースビルドが稼働DBへ接続しなくても再現できる状態を保ちます。

## Gradleタスク

`backend`ディレクトリでは次のタスクを直接実行できます。

```bash
./gradlew migrateDatabase
./gradlew checkCurrentSchemaTag
./gradlew verifyDatabaseMigrations
./gradlew jooqCodegen
./gradlew migrateAndGenerateJooq
```

- `migrateDatabase`：`databaseSchemaTag`まで`updateToTag`を実行し、そのタグの存在を確認します。
- `checkCurrentSchemaTag`：現在のスキーマタグが対象DBに存在することを確認します。
- `verifyDatabaseMigrations`：使い捨てDBで全changesetの適用、rollback、再適用、現在タグの存在確認を実行します。
- `jooqCodegen`：現在のDBスキーマを読み取り、コード生成だけを実行します。
- `migrateAndGenerateJooq`：現在のスキーマタグまで適用してから`jooqCodegen`を実行します。

`verifyDatabaseMigrations`は適用済みchangesetを実際に戻すため、使い捨てDB専用です。
開発DBと本番DBでは実行しません。

Liquibaseプラグインの`update`はchangelog内の未適用changesetをすべて適用します。
現在タグより後の開発中changesetも対象になるため、本番のマイグレーション入口には使用しません。

## 参照資料

- Liquibase Gradle Plugin Usage: <https://github.com/liquibase/liquibase-gradle-plugin/blob/main/doc/usage.md>
