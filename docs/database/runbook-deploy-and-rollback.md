---
type: Runbook
title: 'DBのデプロイと切り戻し'
description: 本番へスキーマ変更をデプロイする順序と、スキーマタグを指定してDBを切り戻す手順を示す。本番マイグレーションのパイプラインを組むとき、DBを切り戻すときに読む。
tags: [runbook, database, liquibase, deploy, rollback]
---

# DBのデプロイと切り戻し

本番のマイグレーションは単一のジョブが`task be-release-migrate`で実行し、成功した場合だけアプリケーションをデプロイします。
切り戻しは、書き込み停止、バックアップ確認、プレビューの確認を終えてから、`CONFIRM_ROLLBACK=yes`を付けて実行します。
rollbackでも削除済みデータの復元は保証されません。

## デプロイ順序

後方互換性を保つchangesetでは、デプロイを次の順序に分けます。

1. CIが使い捨てDBでマイグレーションとrollback再適用を検証します。
2. リリース対象のchangeset、バックアップ、復旧手順を承認します。
3. 単一のマイグレーションジョブが`task be-release-migrate`を実行します。
4. `updateToTag`と`assertSchemaTagExists`が成功した場合だけアプリケーションをデプロイします。
5. デプロイ履歴へアプリケーション識別子とDBスキーマタグを記録します。

```bash
task be-release-migrate
```

本番でタグ名を手入力しません。
デプロイジョブはリポジトリに固定された`databaseSchemaTag`を使用します。
複数のアプリケーションインスタンスから同時にマイグレーションを起動しません。

破壊的変更はexpand-and-contractで分割し、旧バージョンと新バージョンが併存する時間帯に必要な列やテーブルを先に削除しない構成にします。

## DB切り戻し

DB切り戻しは指定タグより後に適用されたchangesetを新しい順に戻します。
テーブルや列を再作成できるrollbackでも、削除済みデータの復元は保証されません。

本番の切り戻し対象タグは作業者が記憶や推測で選ばず、直前に成功したデプロイ履歴からパイプラインが取得します。
現時点では本番デプロイワークフローがないため、Taskfileのタスクへ`DB_ROLLBACK_TAG`として明示します。

切り戻し前にアプリケーションの書き込みを停止し、対象DBのバックアップと復元手順を確認します。
その後、タグの存在とLiquibaseが生成するSQLを確認します。

```bash
task be-rollback-check DB_ROLLBACK_TAG=schema-v1
task be-rollback-preview DB_ROLLBACK_TAG=schema-v1
```

プレビューSQLは`backend/build/reports/liquibase/rollback-preview.sql`へ出力されます。
SQLの対象オブジェクト、データ損失、ロック時間、切り戻し後に起動するアプリケーションとの互換性を確認します。

確認後に限り、明示確認を付けて切り戻します。

```bash
task be-rollback \
  DB_ROLLBACK_TAG=schema-v1 \
  CONFIRM_ROLLBACK=yes
```

`DB_ROLLBACK_TAG`と`CONFIRM_ROLLBACK=yes`がなければTaskfileのタスクはDBへ接続せず失敗します。
Gradleを直接使う場合も、`-PliquibaseTag=<tag>`と`-PconfirmRollback=true`が必要です。

```bash
./gradlew checkRollbackTag \
  -PliquibaseTag=schema-v1
./gradlew previewDatabaseRollback \
  -PliquibaseTag=schema-v1 \
  -PliquibaseOutputFile=build/reports/liquibase/rollback-preview.sql
./gradlew rollbackDatabase \
  -PliquibaseTag=schema-v1 \
  -PconfirmRollback=true
```

切り戻し後は`DATABASECHANGELOG`とDBスキーマを確認し、そのスキーマと互換性のあるアプリケーションをデプロイします。
rollback定義のないchangesetやデータ復元が必要な障害では、このコマンドだけに依存せず、承認済みの復旧手順とバックアップを使用します。

## 参照資料

- Liquibase `update-to-tag`: <https://docs.liquibase.com/commands/update/update-to-tag.html>
- Liquibase Rollback: <https://docs.liquibase.com/community/reference-guide-5-0-4/init-update-and-rollback-commands/rollback>
