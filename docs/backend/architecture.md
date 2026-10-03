---
type: Architecture
title: バックエンドアーキテクチャ
description: Package by feature とオニオンアーキテクチャによるバックエンドの構造を説明する。機能モジュールのパッケージ構成とクラスの役割名、モジュールルートの公開契約、依存方向、モジュール間の連携、共有モジュール shared の範囲、ベースパッケージ直下の全体設定を確認するときに読む。
tags: [architecture, backend, spring-modulith]
---

# バックエンドアーキテクチャ

最上位を機能で分割し、各機能モジュールを `com.example.demo.<feature>` に置く。
機能の内部はオニオンアーキテクチャとし、クラスを役割ごとに決まったパッケージと名前で置く。
他の機能モジュールとは、モジュールルートのイベントと `<Feature>Queries` だけで連携する。
各層の責務は [バックエンドの層の責務](layers.md) に示す。

## 方針

バックエンドは、最上位を機能で分割する Package by feature を採用する。
各機能の内部にはオニオンアーキテクチャを適用し、業務ロジックから技術詳細へ向かう依存を禁止する。
この方針は [ADR-002](../adr/ADR-002-package-by-feature-onion-architecture.md) で決定している。

Spring Modulith は `com.example.demo` の直接サブパッケージをアプリケーションモジュールとして認識するため、**機能モジュール**を `com.example.demo.<feature>` に置く。

クラスの役割、置き場所、命名は [ADR-050](../adr/ADR-050-define-backend-class-roles-and-naming.md) で決定している。
更新は `<UseCase>CommandHandler`、参照は `<Feature>QueryService`、イベントの受信は `<Event>Listener` が担う。

## パッケージ構成

機能モジュールのパッケージとクラスは、次の構成と名前にする。

``` text
backend/src/main/java/com/example/demo/
├── DemoApplication.java
├── SecurityConfig.java
├── OpenApiConfig.java
├── OpenTelemetryAppenderInitializer.java
├── LocaleSupport.java
├── WebLocaleConfig.java
├── package-info.java
│
├── shared/
│   ├── package-info.java
│   └── infrastructure/
│       └── persistence/
│           ├── package-info.java
│           └── <jOOQ の共通処理>.java
│
└── <feature>/
    ├── package-info.java
    ├── <Feature>Queries.java
    ├── <QueryResult>.java
    ├── <SearchCriteria>.java
    ├── <Event>.java
    │
    ├── domain/
    │   ├── model/
    │   │   ├── package-info.java
    │   │   ├── <Aggregate>.java
    │   │   ├── <Entity>.java
    │   │   ├── <ValueObject>.java
    │   │   ├── <Aggregate>Repository.java
    │   │   ├── <Aggregate>ConflictException.java
    │   │   └── <ExternalSystem>.java
    │   └── service/
    │       ├── package-info.java
    │       └── <DomainService>.java
    │
    ├── application/
    │   ├── package-info.java
    │   ├── <UseCase>Command.java
    │   ├── <UseCase>CommandHandler.java
    │   ├── <UseCase>Result.java
    │   ├── <Feature>QueryService.java
    │   └── <Event>Listener.java
    │
    ├── presentation/
    │   └── web/
    │       ├── package-info.java
    │       ├── <Aggregate>Controller.java
    │       ├── <UseCase>Request.java
    │       └── <QueryResult>Response.java
    │
    └── infrastructure/
        ├── persistence/
        │   ├── package-info.java
        │   └── Jooq<Aggregate>Repository.java
        └── client/
            ├── package-info.java
            └── <ExternalSystem>Client.java
```

`<Event>Listener` は、イベントを受信する側のモジュールの `application` に置く。

各役割の定義、必須の記述、例、作成時のチェックリストは、次の文書に示す。
役割の一覧は [クラスの役割](class-roles/index.md) にある。

- **`<Feature>Queries`**：[参照のインタフェース](class-roles/feature-queries.md)
- **`<QueryResult>`**：[参照の結果](class-roles/query-result.md)
- **`<SearchCriteria>`**：[検索条件](class-roles/search-criteria.md)
- **`<Event>`**：[イベント](class-roles/event.md)
- **`<Aggregate>`**：[集約](class-roles/aggregate.md)
- **`<Entity>`**：[Entity](class-roles/entity.md)
- **`<ValueObject>`**：[値オブジェクト](class-roles/value-object.md)
- **`<Aggregate>Repository`**：[Repository](class-roles/repository.md)
- **`<Aggregate>ConflictException`**：[集約](class-roles/aggregate.md)
- **`<ExternalSystem>`**：[外部システムのインタフェース](class-roles/external-system-interface.md)
- **`<DomainService>`**：[Domain Service](class-roles/domain-service.md)
- **`<UseCase>Command`**：[Command](class-roles/command.md)
- **`<UseCase>CommandHandler`**：[CommandHandler](class-roles/command-handler.md)
- **`<UseCase>Result`**：[Result](class-roles/result.md)
- **`<Feature>QueryService`**：[QueryService](class-roles/query-service.md)
- **`<Event>Listener`**：[Listener](class-roles/listener.md)
- **`<Aggregate>Controller`**：[Controller](class-roles/controller.md)
- **`<UseCase>Request`**：[Request](class-roles/request.md)
- **`<QueryResult>Response`**：[Response](class-roles/response.md)
- **`Jooq<Aggregate>Repository`**：[jOOQ の Repository](class-roles/jooq-repository.md)
- **`<ExternalSystem>Client`**：[外部システムの Client](class-roles/external-client.md)

空のパッケージは、そのパッケージの最初のクラスより先に作らない。

Java パッケージを作るときは、`@NullMarked` を宣言する `package-info.java` も作る。

## モジュールルート

**モジュールルート**は `com.example.demo.<feature>` 直下のパッケージであり、他の機能モジュールへ公開する契約だけを置く。

ルートに置ける型は、record、enum、`<Feature>Queries` インタフェースだけである。

- **`<Feature>Queries`**：機能の参照を提供するインタフェース。すべての機能モジュールに作り、自モジュールの Controller と他モジュールがこれを使う。
- **`<QueryResult>`**：参照の結果を表す record。
- **`<SearchCriteria>`**：検索条件を表す record。
- **`<Event>`**：状態の変化を他モジュールと自モジュールの後続の処理へ通知する、過去形の名前のイベントの record。

ルートの型は、`String`、`Instant`、`BigDecimal` などの Java の標準型と、同じルートパッケージの型だけを持つ。
Domain の型、Command と Result、Spring MVC の Request と Response、jOOQ の生成型は置かない。

機能モジュールは Spring Modulith の既定の閉じたモジュールとし、`@ApplicationModule` と `@NamedInterface` を付けない。

## 共有モジュール shared

`com.example.demo.shared` は、機能モジュールではない唯一の技術的な共有モジュールである。
`@Modulithic(sharedModules = "shared")` で Spring Modulith の shared モジュールにし、決定は [ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md) に示す。

- jOOQ の共通処理を `shared.infrastructure.persistence` に置き、このパッケージを `@NamedInterface` で公開する。
  楽観的ロックの更新件数を判定する `OptimisticLock` もここに置く（[ADR-052](../adr/ADR-052-detect-optimistic-lock-conflicts-by-update-count.md)）。
- 業務の概念を置かない。
  ルートのパッケージには `package-info.java` だけを置く。
- `shared` を使ってよいのは、他のモジュールの `infrastructure.persistence` だけである。

使う場所の制限は、`PackageByFeatureOnionArchitectureTest` の `sharedModuleIsUsedOnlyByPersistenceAdapters` が検査する。
ただし、`shared` の `PgmCdAspect` は、AOP で `<UseCase>CommandHandler` の `handle` と `<Event>Listener` の呼び出しを囲む（[ADR-051](../adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。
この呼び出しはクラスの依存にならないため、ArchUnit では検査できない。

`shared` は機能モジュールではないため、「モジュール間の連携」の対象にならない。
機能モジュールの間の連携は、`shared` があってもイベントと `<Feature>Queries` だけにする。

## 依存方向

許可する依存方向は次のとおりである。

``` text
presentation.web ──> application ──> domain.service ──> domain.model
       │                 │  │                               ▲
       │                 │  └───────────────────────────────┤
       └──> <feature> <──┘                                  │
infrastructure.persistence ─────────────────────────────────┤
infrastructure.client ──────────────────────────────────────┘
```

- `presentation.web` は `application` とモジュールルートに依存し、Domain には依存しない。
- `application` は `domain.service`、`domain.model`、モジュールルートに依存する。
- `domain.service` は `domain.model` に依存する。
- `infrastructure.persistence` と `infrastructure.client` は `domain.model` のインタフェースを実装し、`application`、`domain.service`、モジュールルートに依存しない。
- `infrastructure.persistence` は `shared.infrastructure.persistence` に依存してよい。
  ほかの層は `shared` に依存しない。
- Domain と Application は、Presentation と Infrastructure に依存しない。
- Presentation、Persistence、外部 Client は相互に依存しない。

## モジュール間の連携

機能モジュールの間の連携は、次の二つだけにする。

- **イベント**：状態の変更を伝える。発行側の CommandHandler がルートの `<Event>` を `ApplicationEventPublisher` で発行し、受信側の `<Event>Listener` が受ける。
- **参照**：相手のルートにある `<Feature>Queries` を呼ぶ。戻り値はルートの record である。

他モジュールの状態を同期で変更しない。
同期の状態変更が必要に見えたら、実装を止めて利用者に確認し、ADR を起こす。

他モジュールの `domain`、`application`、`presentation`、`infrastructure` は内部パッケージなので参照しない。
CommandHandler は `application` にあるため、他モジュールから呼ぶと `ApplicationModuleArchitectureTest` の `ApplicationModules.verify()` が失敗する。
検査の内容は [バックエンドのアーキテクチャテスト](architecture-tests.md) に示す。

## 全体設定

`com.example.demo` 直下には、アプリケーションの起動クラスと機能横断の設定だけを置く。

機能固有の Bean や業務ロジックをベースパッケージ直下へ追加しない。

共通パッケージを `com.example.demo` の直接サブパッケージへ作ると、Spring Modulith が機能モジュールとして認識する。
機能横断の API エラー契約は、`error` モジュールの `presentation.web` に置いている。

複数の機能で似た処理が要るときも、処理は各機能内に置く。
例外は、「共有モジュール shared」に示す jOOQ の共通処理だけである。

## 関連資料

- [ADR-002: package by feature とオニオンアーキテクチャ](../adr/ADR-002-package-by-feature-onion-architecture.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](../adr/ADR-050-define-backend-class-roles-and-naming.md)
- [バックエンドの層の責務](layers.md)
- [バックエンドの Java 実装規約](java-coding.md)
- [バックエンドのアーキテクチャテスト](architecture-tests.md)
- [バックエンドの機能追加時の確認](runbook-add-feature.md)
- [バックエンドの文書一覧（テスト規約を含む）](index.md)
