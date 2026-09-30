---
type: Convention
title: 'DB接続情報とロール分離'
description: アプリケーション、Liquibase、jOOQ生成が使う接続先と資格情報の環境変数、スキーマの所有、環境ごとのロール分離を定める。接続用の環境変数やDBロールを追加、変更するとき、ステージングや本番のDBを準備するときに読む。
tags: [convention, database, liquibase, security, credentials]
---

# DB接続情報とロール分離

接続先（`DB_HOST`、`DB_PORT`、`DB_NAME`）はアプリケーションとマイグレーションで共有し、資格情報だけを役割ごとに分けます。
アプリケーションは`DB_USERNAME`（DMLのみ）、LiquibaseとjOOQ生成は`MIGRATION_DB_USERNAME`（DDL）で接続し、資格情報にフォールバックはありません。
接続情報は`build.gradle`へ保存せず、環境変数またはCIのシークレットから注入します。

## 接続情報

接続情報は`build.gradle`へ保存せず、環境変数またはCIのシークレットから注入します。
ユーザー名とパスワードが未設定のままDB操作タスクを実行すると、タスクは接続前に失敗します。

接続変数の区分は、同じ環境内に別々の物理DBを用意するためのものではありません。
開発、CI、ステージング、本番のDBは環境ごとに分離しますが、同じ環境のアプリケーションとLiquibaseは同じデータベースへ異なる権限で接続します。

接続先（ホスト、ポート、DB名）はアプリケーションとマイグレーションで共有し、役割ごとに変わるのは資格情報だけです。
スキーマ名は環境差を持たないため、ビルド設定とchangesetで固定します。

- **共通の接続先**：`DB_HOST`、`DB_PORT`、`DB_NAME`を使います。
  アプリケーション、Liquibase、jOOQ生成のいずれもこのDBへ接続します。
- **Liquibase管理スキーマ**：`liquibase`に固定します。
  Liquibaseがchangesetより先に管理テーブルを作るため、DB初期化はこのスキーマだけを事前作成します。
- **モジュール所有スキーマ**：Liquibase changesetで作成します。
  現在はSpring Modulith共有テーブル用の`modulith`だけです。
  jOOQの生成対象は`backend/gradle/database.gradle`の一覧で管理します。
- **アプリケーション接続の資格情報**：`DB_USERNAME`、`DB_PASSWORD`を使います。
  Spring Bootは共通の接続先とこの資格情報からJDBC URLを組み立てます。
- **マイグレーション接続の資格情報**：`MIGRATION_DB_USERNAME`、`MIGRATION_DB_PASSWORD`を使います。
  Liquibaseとその接続を共用するjOOQ生成が使います。
  接続先は共通の`DB_*`から組み立て、資格情報だけを差し替えます。
  ステージングと本番では、DDL権限を持つマイグレーション専用アカウントを指定します。
  jOOQコード生成はローカルとCIでのみ実行し、本番では実行しないため、生成専用の資格情報は設けません。
- **Gradle用URLの上書き**：`DB_URL`はGradleの共通接続先を上書きします。
  Spring Bootのデータソースは`DB_URL`を参照せず、`DB_HOST`、`DB_PORT`、`DB_NAME`からURLを組み立てます。

## ロール分離

資格情報にフォールバックはありません。
マイグレーションとjOOQ生成を実行するときは、`MIGRATION_DB_USERNAME`と`MIGRATION_DB_PASSWORD`を全環境で明示します。
これにより、ローカルからテスト、ステージング、本番まで、接続の解決経路と権限モデルが一致し、環境ごとに異なる分岐を通りません。
ローカルとテストも二ロールで動かします。
`docker/compose.yml`と`docker/compose-test.yml`では、DDL権限を持つマイグレーション用ロールがPostgreSQLのブートストラップユーザーになり、`liquibase`スキーマとDML限定のアプリケーション用ロールを`docker/initdb`が初回起動時に作成します。
001 changesetは`modulith`スキーマを作成し、そのUSAGEとイベント出版テーブルだけのDML権限を`DB_USERNAME`へ付与します。
Liquibase管理スキーマと管理テーブルへのアプリ権限は付与しません。
001 changesetを初期状態から書き換えたため、既存DBは移行せず`task compose-reset CONFIRM_RESET=yes`で作り直します。
ステージングと本番も既存DBを再利用せず、`liquibase`スキーマだけをマイグレーション用ロールの所有で作ってから初回マイグレーションを実行します。
001 changesetがアプリケーションロール（`DB_USERNAME`）へGRANTするため、ステージングと本番では、そのロールを初回マイグレーションより前にRDS側で作成しておきます。
`docker/initdb`のような自動作成経路がないため、未作成のままマイグレーションを実行するとGRANTで失敗します。
`be-release-migrate`とDB切り戻し用Taskfileのタスクはローカルの`.env`を読み込まず、共通の接続先（`DB_URL`、または`DB_HOST`/`DB_PORT`/`DB_NAME`）と権限付与先の`DB_USERNAME`、`MIGRATION_DB_USERNAME`、`MIGRATION_DB_PASSWORD`が注入されていなければGradle実行前に失敗します。
アプリケーション用アカウントには業務処理に必要なDML権限を与え、`CREATE`、`ALTER`、`DROP`を与えません。
Liquibase用アカウントには、DDL、Liquibase管理テーブルの更新、データ移行changesetに必要なDMLの権限を与えます。

| 環境         | 接続先       | アプリケーションロール（DML） | マイグレーションロール（DDL、所有）  | jOOQ生成           |
| ------------ | ------------ | ----------------------------- | ------------------------------------ | ------------------ |
| ローカル     | 共通の`DB_*` | `demo`（initdbが作成）        | `demo_migration`（ブートストラップ） | 同じ接続で生成     |
| CIテスト     | 共通の`DB_*` | `demo`（initdbが作成）        | `demo_migration`（ブートストラップ） | 必要な場合だけ生成 |
| ステージング | 共通の`DB_*` | 専用アカウント                | 専用アカウント                       | 実行しない         |
| 本番         | 共通の`DB_*` | 専用アカウント                | 専用アカウント                       | 実行しない         |

本番でjOOQコードを生成しない理由は[jOOQコード生成と日時型](jooq-codegen.md)にあります。

## 参照資料

- Spring Boot Common Application Properties: <https://docs.spring.io/spring-boot/appendix/application-properties/index.html>
