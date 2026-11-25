---
type: Runbook
title: バックエンドの機能追加時の確認
description: 新しい機能モジュールを追加するときの配置の順序と、アーキテクチャテストと静的解析の実行コマンドを示す。機能モジュールを新しく作るとき、機能追加後に検証するときに読む。
tags: [runbook, backend, spring-modulith]
---

# バックエンドの機能追加時の確認

機能モジュールを作り、モジュールルート、Domain、Application、Presentation、Infrastructure の順にクラスを置く。
最後にアーキテクチャテストと静的解析を実行する。
各層の役割は [バックエンドの層の責務](layers.md) に、パッケージ構成とモジュールルートの規則は [バックエンドアーキテクチャ](architecture.md) に示す。

## 配置の順序

新しい機能を追加するときは、次の順序でクラスを置く。

1. Spring Modulith の機能モジュール名を決め、`com.example.demo.<feature>` を作る。
   ルートの `package-info.java` に、依存してよいモジュールを `@ApplicationModule(allowedDependencies = ...)` で宣言する。
   書かないと `ApplicationModuleArchitectureTest` が失敗する。
   書き方は [バックエンドアーキテクチャ](architecture.md) の「モジュール間の連携」に従う。
2. モジュールルートに `<Feature>Queries`、参照の結果と検索条件の record、他モジュールと自モジュールの後続の処理へ通知するイベントの record を置く。
3. `domain.model` に集約、Entity、値オブジェクト、`<Aggregate>Repository`、外部システムのインタフェースを置く。
4. 集約にも値オブジェクトにも置けない業務規則を、`domain.service` の Domain Service に置く。
5. `application` に、状態を変えるユースケースごとの `<UseCase>Command`、`<UseCase>CommandHandler`、`<UseCase>Result` と、`<Feature>QueryService` を置く。他モジュールのイベントを受けるとき、外部システムを呼ぶときは、`<Event>Listener` も置く。
6. `presentation.web` に `<Aggregate>Controller`、`<UseCase>Request`、`<QueryResult>Response` を置く。Javadoc とアノテーションは [OpenAPIのアノテーションとJavadoc](../web-api/openapi-annotations.md) に従い、契約と生成物の更新は [APIを変更する](../web-api/runbook-api-change.md) の手順で行う。
7. `infrastructure.persistence` に `Jooq<Aggregate>Repository` を、`infrastructure.client` に `<ExternalSystem>Client` を置く。
8. 作成した各 Java パッケージへ `@NullMarked` の `package-info.java` を追加する。
9. アーキテクチャテストと対象機能のテストを実行する。

## パッケージの外で直す場所

機能モジュールが DB のスキーマを持つときは、パッケージの外も直す。
足し忘れたときに止まるものを、直す場所ごとに示す。

- `backend/gradle/database.gradle` の `databaseSchemas` にスキーマ名を足す。足し忘れると、jOOQ の型が生成されず、コンパイルで止まる。
- Liquibase の changeset に、`CREATE SCHEMA` とアプリのロールへの `GRANT` を書く（[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)）。足し忘れると、SQL が `permission denied for schema` で失敗する。
- `ArchitectureMetricsReportTest` の `MAX_GLOBAL_RELATIVE_VISIBILITY` を、公開の型が増えた分だけ見直す。公開の型が増えて上限を超えると、アーキテクチャテストが止まる。

## 検証コマンド

アーキテクチャテストは次のコマンドで実行できる。

``` bash
cd backend
./gradlew test --tests 'com.example.demo.architecture.*'
```

`test` はカバレッジの検証を実行しないため、一部だけを実行しても失敗しない（[バックエンドのテストの実行基盤](testing-infrastructure.md)）。

テストの内容は [バックエンドのアーキテクチャテスト](architecture-tests.md) に示す。

静的解析はワークスペースルートで次のコマンドを実行する。

``` bash
task be-lint
```
