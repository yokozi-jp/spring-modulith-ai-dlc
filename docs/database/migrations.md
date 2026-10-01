---
type: Convention
title: DBマイグレーション規約
description: Liquibase changesetとスキーマタグの追加規約、マイグレーションの実行経路、CIでのrollback検証を定め、changesetやスキーマタグを追加する、または実行経路や検査方法を変更するときに読む。
tags: [convention, database, liquibase, migration]
---

# DBマイグレーション規約

アプリケーションは起動時にLiquibaseを実行せず、スキーマ変更はデプロイ前にGradleタスクで明示的に適用します。
スキーマタグはchangelogに`tagDatabase` changesetとして書き、現在のタグを`backend/gradle.properties`の`databaseSchemaTag`に記録します。
CIは使い捨てDBで適用、rollback、再適用を検証します。
この実行モデルと検証を採用した理由は[ADR-005](../adr/ADR-005-decouple-liquibase-from-app-startup.md)を参照してください。

## 実行モデル

アプリケーションは起動時にLiquibaseを実行しません。
`spring-boot-starter-liquibase`を実行時クラスパスから除外しているため、Spring Bootの自動構成（`LiquibaseAutoConfiguration`）が読み込まれず、起動時マイグレーションは起こりません。

スキーマ変更はアプリケーションのデプロイ前に、独立したジョブまたは作業者がGradleタスクを明示して適用します。
通常の`compileJava`、`test`、`bootJar`はLiquibaseとjOOQコード生成を起動せず、DBにも接続しません。
適用に使うコマンドは[DB操作コマンドの早見表](commands.md)にあります。

## スキーマタグ

**スキーマタグ**は、DBスキーマのある状態に付けた名前です。
デプロイやマイグレーションの区切りとなる、復旧の目印として使います。

このタグは、稼働中のDBへ`tag`コマンドを手で打って付けるのではなく、changelogファイルの中に`tagDatabase` changesetとして書いておきます。
changelogはGitでコードと一緒に版管理されるので、タグもコードと同じ履歴で追えます。
稼働DBの状態に手を入れて付けるのではなく、ファイルに書いて管理する方式なので、これを宣言的と呼びます（対義語は、コマンドを打って付ける命令的な方式です）。

現在のスキーマタグは`backend/gradle.properties`の`databaseSchemaTag`に記録し、`migrateDatabase`が同じタグを`updateToTag`へ自動的に渡します。

次のスキーマ版を追加するときは、スキーマ変更と独立したタグchangesetを連番順に追加します。
`tagDatabase`は他のChange Typeと同じchangesetへ入れません。
タグchangesetの追加と同じ変更で、`backend/gradle.properties`の`databaseSchemaTag`を更新します。
`validateCurrentSchemaTag`は、このタグが連番changelogの末尾に一度だけ宣言されていることをDB接続前に検証します。

DBスキーマタグはDB変更の復旧点であり、アプリケーションのリリース番号ではありません。
DB変更のないアプリケーションリリースでは、新しいDBスキーマタグを作りません。
アプリケーションリリースはGitタグまたはコンテナイメージdigestで識別し、デプロイ履歴へ使用したDBスキーマタグを記録します。

命令的なGradleの`tag`タスクは誤操作を防ぐため失敗します。

## CIでの検証

バックエンドCIは使い捨てPostgreSQLに対して`verifyDatabaseMigrations`を実行します。
Liquibaseの`updateTestingRollback`によって、全changesetを適用し、同じchangesetを戻し、再び適用します。
その後に`assertSchemaTagExists`で現在のスキーマタグを確認し、通常のマイグレーションとバックエンドテストを実行します。

この検証により、rollback定義の不足とタグchangesetの追加漏れをPull Requestで検出します。
本番DBのデータ復元可能性までは保証しません。

## 参照資料

- Spring Boot Database Initialization: <https://docs.spring.io/spring-boot/how-to/data-initialization.html>
- Liquibase `tagDatabase`: <https://docs.liquibase.com/reference-guide/change-types/tagDatabase>
- Liquibase `update-to-tag`: <https://docs.liquibase.com/commands/update/update-to-tag.html>
- Liquibase `update-testing-rollback`: <https://docs.liquibase.com/commands/update/update-testing-rollback.html>
