---
type: Architecture
title: バックエンドアーキテクチャ
description: Package by feature とオニオンアーキテクチャによるバックエンドの構造を説明する。機能モジュールのパッケージ構成とクラスの役割名、モジュールルートの公開契約、依存方向、モジュール間の連携、ベースパッケージ直下の全体設定を確認するときに読む。
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
`<feature>` のモジュール名はスキーマ名と一致するため、SQL の予約語にしない（`order` ではなく `ordering`、[PostgreSQLの命名規約](../database/postgresql-naming.md)の「使わない名前」）。

``` text
backend/src/main/java/com/example/demo/
├── DemoApplication.java
├── SecurityConfig.java
├── OidcDiscoveryLocationConfig.java
├── OpenApiConfig.java
├── OpenTelemetryAppenderInitializer.java
├── LocaleSupport.java
├── WebLocaleConfig.java
├── package-info.java
│
├── shared/
│   ├── package-info.java
│   ├── batch/
│   │   ├── package-info.java
│   │   ├── CatchUpJobRunner.java
│   │   ├── JobPeriodRule.java
│   │   └── TenantPartitionAspect.java
│   ├── concurrency/
│   │   ├── package-info.java
│   │   ├── ConflictException.java
│   │   ├── ExpectedLockNo.java
│   │   └── VersionedCommand.java
│   ├── failure/
│   │   ├── package-info.java
│   │   ├── BusinessRuleViolationException.java
│   │   └── NotFoundException.java
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
    ├── batch/
    │   ├── package-info.java
    │   ├── <Job>JobConfig.java
    │   ├── <Job>ItemReader.java
    │   ├── <Job>ItemWriter.java
    │   └── <Job>Tasklet.java
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
- **`ConflictException`**：[業務上の失敗の例外](class-roles/business-exception.md)（`shared.concurrency`）
- **`NotFoundException`**、**`BusinessRuleViolationException`**：[業務上の失敗の例外](class-roles/business-exception.md)（`shared.failure`）
- **`ExpectedLockNo`**、**`VersionedCommand`**：[Command](class-roles/command.md)（`shared.concurrency`）
- **`<ExternalSystem>`**：[外部システムのインタフェース](class-roles/external-system-interface.md)
- **`<DomainService>`**：[Domain Service](class-roles/domain-service.md)
- **`<UseCase>Command`**：[Command](class-roles/command.md)
- **`<UseCase>CommandHandler`**：[CommandHandler](class-roles/command-handler.md)
- **`<UseCase>Result`**：[Result](class-roles/result.md)
- **`<Feature>QueryService`**：[QueryService](class-roles/query-service.md)
- **`<Event>Listener`**：[Listener](class-roles/listener.md)
- **`<Job>JobConfig`**：[Job の設定](class-roles/job-config.md)
- **`<Job>ItemReader`**：[ItemReader](class-roles/item-reader.md)
- **`<Job>ItemWriter`**：[ItemWriter](class-roles/item-writer.md)
- **`<Job>Tasklet`**：[Tasklet](class-roles/tasklet.md)
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

機能モジュールは Spring Modulith の既定の閉じたモジュールとし、`@NamedInterface` を付けない。
依存先の宣言は「モジュール間の連携」に従う。

## 共有モジュール shared

`com.example.demo.shared` は、機能モジュールではない唯一の技術的な共有モジュールである（[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）。
置くパッケージと型、使ってよい層、検査は [共有モジュール shared](shared-module.md) に示す。

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
- `batch` は `application` の Command、Result、CommandHandler、自分のモジュールのルート、`tenancy` のルート、`shared.batch` に依存し、Domain と Infrastructure に依存しない。
  `batch` の依存方向はまだ ArchUnit で検査していない（[#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667)、[バックエンドのアーキテクチャテスト](architecture-tests.md)）。
- `infrastructure.persistence` と `infrastructure.client` は `domain.model` のインタフェースを実装し、`application`、`domain.service`、モジュールルートに依存しない。
- `infrastructure.persistence` は `shared.infrastructure.persistence` に依存してよい。
  ほかの層は `shared.infrastructure.persistence` に依存しない。
- どの層も、[共有モジュール shared](shared-module.md) の「どの層からも使える」パッケージに依存してよい。`error` の `presentation.web` も、4 つの例外を 403、404、409、422 にするために依存する。
- モジュールルートの型は、標準型と同じルートの型のほかに、イベントが運ぶテナントの `shared.tenancy.TenantId` と `shared.tenancy.TenantScoped` だけに依存してよい。`TenantContext` には依存できない。
- Domain と Application は、Presentation、Batch、Infrastructure に依存しない。
- Presentation、Batch、Persistence、外部 Client は相互に依存しない。

## モジュール間の連携

モジュールが依存してよいモジュールは、そのモジュールのルートの `package-info.java` に `@ApplicationModule(allowedDependencies = ...)` で宣言する。
宣言にないモジュールへの依存は、`ApplicationModules.verify()` が `Module 'payment' depends on module 'product'` のように失敗にする。
`jooq` は OPEN のモジュールなので、jOOQ の生成型を使うモジュールは `"jooq"` を書く。
`@Modulithic(sharedModules = "shared")` で暗黙の許可先に入るのは `shared` のルートだけで、名前付きインタフェースは入らない。
`shared` の名前付きインタフェースを使うモジュールは `"shared :: *"` を書く。
現在の値は各モジュールの `package-info.java` が正本である（決定時の一覧は [ADR-118](../adr/ADR-118-declare-module-dependencies-with-allowed-dependencies.md)）。

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
例外は、[共有モジュール shared](shared-module.md) に示す jOOQ の共通処理と楽観的ロックの語彙と業務上の失敗の例外と、イベント出版の再投入とその方針の注釈と、業務のバッチの追いつきの Runner、テナントを束縛する Aspect、期間の規則だけである。

## 参照業務機能

`product`、`ordering`、`payment` は、AI harness を実際の業務の流れで継続して検証する参照業務機能である（[README](../../README.md)）。
3 つは通常の機能モジュールであり、参照業務機能だけの層やパッケージを持たない。
機能モジュールを追加するときは、この 3 つを実例として読み、[バックエンドの機能追加時の確認](runbook-add-feature.md)に従う。
3 つの変更も、API の契約の版とマイグレーションの通常の規約に従う。

参照業務機能は、注文と決済の API を認証済みの利用者だけに許し、注文の持ち主を確かめない。
このため、認証済みの利用者なら誰でも、ほかの利用者の注文と決済記録を読み、下書きの注文を変更、確定、取消できる。
これは参照業務機能の割り切りであり、実例として持ち主の確認を写さない。
持ち主に限る認可は [#188](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/188) で扱う。

## 関連資料

- [ADR-002: package by feature とオニオンアーキテクチャ](../adr/ADR-002-package-by-feature-onion-architecture.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](../adr/ADR-050-define-backend-class-roles-and-naming.md)
- [バックエンドの層の責務](layers.md)
- [共有モジュール shared](shared-module.md)
- [バックエンドの Java 実装規約](java-coding.md)
- [バックエンドのアーキテクチャテスト](architecture-tests.md)
- [バックエンドの機能追加時の確認](runbook-add-feature.md)
- [バックエンドの文書一覧（テスト規約を含む）](index.md)
