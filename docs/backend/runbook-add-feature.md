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
2. モジュールルートに `<Feature>Queries`、参照の結果と検索条件の record、他モジュールへ通知するイベントの record を置く。
3. `domain.model` に集約、Entity、値オブジェクト、`<Aggregate>Repository`、外部システムのインタフェースを置く。
4. 集約にも値オブジェクトにも置けない業務規則を、`domain.service` の Domain Service に置く。
5. `application` に、状態を変えるユースケースごとの `<UseCase>Command`、`<UseCase>CommandHandler`、`<UseCase>Result` と、`<Feature>QueryService` を置く。他モジュールのイベントを受けるときは、`<Event>Listener` も置く。
6. `presentation.web` に `<Aggregate>Controller`、`<UseCase>Request`、`<QueryResult>Response` を置く。
7. `infrastructure.persistence` に `Jooq<Aggregate>Repository` を、`infrastructure.client` に `<ExternalSystem>Client` を置く。
8. 作成した各 Java パッケージへ `@NullMarked` の `package-info.java` を追加する。
9. アーキテクチャテストと対象機能のテストを実行する。

## 検証コマンド

アーキテクチャテストは次のコマンドで実行できる。

``` bash
cd backend
./gradlew test --tests 'com.example.demo.architecture.*' -x jacocoTestCoverageVerification
```

テストの一部だけを実行するとカバレッジの基準を満たさないため、`jacocoTestCoverageVerification` を除外する。

テストの内容は [バックエンドのアーキテクチャテスト](architecture-tests.md) に示す。

静的解析はワークスペースルートで次のコマンドを実行する。

``` bash
task be-lint
```
