---
inclusion: fileMatch
fileMatchPattern: ["backend/src/main/resources/db/**", "backend/src/generated/jooq/**", "backend/gradle/database.gradle", "backend/gradle.properties", "docker/initdb/**"]
name: database
description: Liquibase の changeset やスキーマタグ、jOOQ 生成コード、DB 接続の環境変数とロール、DB 操作の Taskfile や Gradle タスク、本番マイグレーションや DB 切り戻しを追加、変更、実行するときに使う。
---

# データベースの作業

詳細は `docs/database/index.md` から必要な文書だけ読む。

## 行動指針

- DB に接続するコマンドを実行する前に、その影響範囲を確かめる。
- 破壊的な操作（切り戻し、DB の作り直し）は、利用者の明示的な指示がなければ実行しない。
- changeset を追加したら、生成コードの再生成と rollback 検証を同じ変更で済ませる。
- 規約を推測で補わず、該当する docs を読んでから書く。

## どの docs を読むか

- changeset やスキーマタグを追加する：`docs/database/migrations.md`
- どのコマンドを実行するか迷う、ローカルで適用や再生成をする：`docs/database/commands.md`
- jOOQ コードを再生成する、日時型を確認する：`docs/database/jooq-codegen.md`
- 接続の環境変数や DB ロールを変える：`docs/database/connections.md`
- 本番マイグレーションや DB 切り戻しを扱う：`docs/database/runbook-deploy-and-rollback.md`
- テーブル、カラム、型、制約、インデックスを設計する：`docs/database/index.md` の `postgresql-` で始まる文書
