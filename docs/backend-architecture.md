# バックエンドアーキテクチャ

## 方針

バックエンドは、最上位を機能で分割する Package by feature を採用する。

各機能の内部にはオニオンアーキテクチャを適用し、業務ロジックから技術詳細へ向かう依存を禁止する。

Spring Modulith は `com.example.demo` の直接サブパッケージをアプリケーションモジュールとして認識するため、**機能モジュール**を `com.example.demo.<feature>` に置く。

依存方向は外側から内側へ限定し、Presentation と Infrastructure は Application を介して Domain のユースケースを実行する。

この方針は [ADR-002](adr/ADR-002-package-by-feature-onion-architecture.md) で決定している。

## パッケージ構成

機能モジュールは、必要な役割が生じたパッケージだけを作る。

```text
backend/src/main/java/com/example/demo/
├── DemoApplication.java
├── SecurityConfig.java
├── OpenTelemetryAppenderInitializer.java
├── package-info.java
│
└── <feature>/
    ├── package-info.java
    ├── <Feature>Operations.java
    ├── <Command>.java
    ├── <Result>.java
    ├── <Event>.java
    │
    ├── domain/
    │   ├── model/
    │   │   ├── package-info.java
    │   │   ├── <Aggregate>.java
    │   │   ├── <Entity>.java
    │   │   └── <ValueObject>.java
    │   └── service/
    │       ├── package-info.java
    │       └── <DomainService>.java
    │
    ├── application/
    │   ├── package-info.java
    │   ├── <UseCase>Service.java
    │   └── port/
    │       ├── package-info.java
    │       └── <RepositoryPort>.java
    │
    ├── presentation/
    │   └── web/
    │       ├── package-info.java
    │       ├── <Feature>Controller.java
    │       ├── <Feature>Request.java
    │       └── <Feature>Response.java
    │
    └── infrastructure/
        ├── persistence/
        │   ├── package-info.java
        │   ├── Jooq<Feature>Repository.java
        │   └── <Feature>RecordMapper.java
        ├── messaging/
        │   ├── package-info.java
        │   ├── <Event>Listener.java
        │   └── <Event>Publisher.java
        └── client/
            ├── package-info.java
            └── <ExternalSystem>Client.java
```

空ディレクトリは先に作らない。

`domain.service`、`application.port`、各 Infrastructure Adapter は、その役割を持つ型が必要になった時点で追加する。

Java パッケージを追加するときは、既存の NullAway と JSpecify の規約に従い、`@NullMarked` を宣言する `package-info.java` も追加する。

## モジュールルート

**モジュールルート**は `com.example.demo.<feature>` 直下のパッケージであり、他の機能モジュールへ公開する契約だけを置く。

公開できる型は、ユースケースインタフェース、コマンド、結果、他モジュールへ通知するイベントである。

Spring MVC の Request と Response、Controller、jOOQ の生成型、Repository 実装は置かない。

入口側の契約はモジュールルートで表現できるため、`application.port.in` は作らない。

外部依存を反転する必要が生じた場合だけ、Application に `port` を追加する。

Spring Modulith の既定の閉じたモジュールで足りるため、通常は `@ApplicationModule` を付けない。

ルート以外のパッケージを公開する必要が生じた場合だけ `@NamedInterface` を使う。

## Domain

業務状態と業務規則を置く内側の領域を **Domain** とする。

`domain.model` には集約、Entity、Value Object、Domain Event、業務不変条件を置く。

`domain.service` には、単一の集約へ自然に置けない業務規則だけを置く。

単なるデータ取得や処理の中継は Domain Service に置かない。

Domain は Spring、jOOQ、JPA、Jackson、Web、DB、外部 API クライアントへ依存させない。

Domain Model を API DTO や永続化 Record として兼用しない。

Domain Service は Spring の `@Service` を付けない通常の Java クラスとし、フレームワークから独立させる。

## Application

ユースケースの進行を担当する内側の領域を **Application** とする。

Application Service はモジュールルートの契約を実装し、Domain Model と Domain Service を組み合わせる。

トランザクション境界は Application Service の public メソッドへ `@Transactional` で明示する。

クラス単位の `@Transactional` は、public 以外のメソッドへ意図せず適用される可能性を避けるため使用しない。

Application は Presentation と Infrastructure の実装へ依存させない。

DB、メッセージブローカー、外部 API へのアクセスを抽象化する必要がある場合は、インタフェースを `application.port` に置く。

Infrastructure はそのインタフェースを実装する。

## Presentation

HTTP や UI からの入力と出力を扱う外側の領域を **Presentation** とする。

`presentation.web` には Spring MVC の Controller、Request、Response、Web 固有の変換処理を置く。

Controller は入力を検証して Application のユースケースを呼び出し、Domain Model をそのまま API レスポンスとして返さない。

Presentation は Infrastructure の実装へ直接依存させない。

## Infrastructure

DB、メッセージブローカー、外部 API との接続を扱う外側の領域を **Infrastructure** とする。

`infrastructure.persistence` には jOOQ を使う Repository 実装と、jOOQ の生成型を Domain 型へ変換する Mapper を置く。

`infrastructure.messaging` にはメッセージの受信、送信、シリアライズ、ブローカー固有の設定を置く。

`infrastructure.client` には外部 API クライアントと、外部形式を内部形式へ変換する処理を置く。

Presentation、Persistence、Messaging、外部 Client は別々の Adapter として扱い、相互に直接依存させない。

メッセージコンシューマーは HTTP と UI を扱う Presentation には含めず、transport 実装として `infrastructure.messaging` に置く。

## 依存方向

許可する主要な依存方向は次のとおりである。

```text
presentation ─┐
persistence ──┤
messaging ────┼─> application ─> domain.service ─> domain.model
client ───────┘
```

外側の Adapter は必要に応じて Domain を直接利用できるが、Domain と Application から外側の実装は参照できない。

機能モジュールをまたぐ参照は、相手モジュールのルートに置いた公開契約だけに限定する。

他モジュールの `domain`、`application`、`presentation`、`infrastructure` は内部パッケージなので参照しない。

## 全体設定

`com.example.demo` 直下には、アプリケーションの起動クラスと機能横断の設定だけを置く。

現在は `DemoApplication`、`SecurityConfig`、`OpenTelemetryAppenderInitializer` が該当する。

機能固有の Bean や業務ロジックをベースパッケージ直下へ追加しない。

共通パッケージを安易に `com.example.demo` の直接サブパッケージへ作ると、Spring Modulith が機能モジュールとして認識するため避ける。

複数の機能で似た処理が必要になっても、共有される概念と安定した境界が明確になるまでは各機能内に置く。

## Java 実装規約

手書きのバックエンドコードでは、Lombok の `@Data` と `@Setter` を使用しない。

Request、Response、Command、Result のようなデータキャリアには、原則として record を使う。

Domain Model の状態は setter で公開せず、業務上の操作と不変条件を表すメソッドを介して変更する。

機能コードのロギングには SLF4J facade を使い、Logback、Log4j、Apache Commons Logging の実装 API を直接参照しない。

ログを出力する手書きクラスには Lombok の `@Slf4j` を付け、Logger フィールドと `LoggerFactory` を直接記述しない。

`@Slf4j` の使用規約はソース上の Logger 型と `LoggerFactory` 参照を PMD で禁止して強制する。

ベースパッケージ直下のロギング基盤設定は実装 API との接続を担うため、ArchUnit の facade 制約だけは対象外とする。

## アーキテクチャテスト

パッケージ構成と依存方向は、次のテストで自動検証する。

```text
backend/src/test/java/com/example/demo/architecture/
├── ApplicationModuleArchitectureTest.java
├── GeneralCodingRulesArchTest.java
└── PackageByFeatureOnionArchitectureTest.java
```

`ApplicationModuleArchitectureTest` は Spring Modulith の `ApplicationModules.verify()` を使い、モジュール間の循環、内部パッケージ参照、明示した許可依存への違反を検出する。

`GeneralCodingRulesArchTest` は標準ストリーム、`java.util.logging`、ロギング実装 API、`@Autowired` によるメソッドインジェクションなど、プロダクションコード全体の規則を検証する。

`PackageByFeatureOnionArchitectureTest` は ArchUnit の `Architectures.onionArchitecture()` を使い、機能モジュール内部の依存を検証する。

オニオン規則は次のパッケージを識別する。

- **Domain Model**：`com.example.demo.*.domain.model..`
- **Domain Service**：`com.example.demo.*.domain.service..`
- **Application Service**：`com.example.demo.*` と `com.example.demo.*.application..`
- **Presentation Adapter**：`com.example.demo.*.presentation..`
- **Persistence Adapter**：`com.example.demo.*.infrastructure.persistence..`
- **Messaging Adapter**：`com.example.demo.*.infrastructure.messaging..`
- **外部 Client Adapter**：`com.example.demo.*.infrastructure.client..`

空の層は許可するが、機能モジュールに追加したクラスが定義済みの層または Adapter のどれにも属さない場合は失敗させる。

ベースパッケージ直下の起動クラスと全体設定だけは、オニオン規則の所属検査から除外する。

同じテストは、オニオン規則だけでは表せない次の配置規則も検証する。

- jOOQ API と生成型は `infrastructure.persistence` だけで利用する。
- 機能ルートの公開契約は `domain`、`application`、`presentation`、`infrastructure` の内部型へ依存しない。
- Domain は Spring、jOOQ、JPA、Jackson に依存しない。
- `@Controller` と `@RestController` を付けた型は `presentation` に置く。
- `@Service` を付けた型は `application` に置く。
- `@Repository` を付けた型は `infrastructure.persistence` に置く。
- クラス単位の `@Transactional` は使用しない。
- `@Transactional` を付けるメソッドは `application` の public メソッドに限定する。

Lombok の `@Data` と `@Setter` はコンパイル後のバイトコードに残らないため、ArchUnit ではなくソースを解析する PMD で禁止する。

同じ PMD 規則で、手書きの Logger フィールド、`LoggerFactory` の直接参照、`getLogger` の static import を禁止し、`@Slf4j` の使用へ統一する。

`@Autowired` を付けた通常メソッドは禁止し、依存注入にはコンストラクタを使う。

ArchUnit は `ProductionCodeOnly` を通して手書きのプロダクションコードだけを解析し、テストコードと生成コードを解析対象から除外する。

生成コード自体を除外しても、手書きコードから jOOQ API や生成型への依存は検査する。

## 機能追加時の確認

新しい機能を追加するときは、次の順序で配置を決める。

1. Spring Modulith の機能モジュール名を決め、`com.example.demo.<feature>` を作る。
2. 他モジュールへ公開する必要がある契約だけをモジュールルートへ置く。
3. 業務状態と業務規則を Domain に置く。
4. ユースケースの進行とトランザクション境界を Application に置く。
5. HTTP 固有の型を Presentation に置く。
6. DB、メッセージブローカー、外部 API 固有の型を対応する Infrastructure Adapter に置く。
7. 作成した各 Java パッケージへ `@NullMarked` の `package-info.java` を追加する。
8. アーキテクチャテストと対象機能のテストを実行する。

アーキテクチャテストは次のコマンドで実行できる。

```bash
cd backend
./gradlew test \
  --tests com.example.demo.architecture.ApplicationModuleArchitectureTest \
  --tests com.example.demo.architecture.GeneralCodingRulesArchTest \
  --tests com.example.demo.architecture.PackageByFeatureOnionArchitectureTest
```

静的解析はワークスペースルートで次のコマンドを実行する。

```bash
task be-lint
```

## 関連資料

- [ADR-002: package by feature とオニオンアーキテクチャ](adr/ADR-002-package-by-feature-onion-architecture.md)
- [Package by feature とオニオンアーキテクチャの調査記録](tmp/package-by-feature-onion-handoff.md)
- [追加を検討する ArchUnit の観点](tmp/archunit-additional-rules.md)
- [バックエンドのテストコード規約](../.kiro/steering/backend-testing-conventions.md)
