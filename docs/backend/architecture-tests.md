---
type: Reference
title: バックエンドのアーキテクチャテスト
description: Spring Modulith、ArchUnit、PMD、Error Proneでバックエンド規約を検査する実装、規則名、規則を確かめるフィクスチャ、解析対象を示し、規約違反の検出方法を確認するとき、ArchUnitの規則を追加するときに読むリファレンス。
tags: [reference, backend, testing, archunit, spring-modulith]
---

# バックエンドのアーキテクチャテスト

Spring ModulithとArchUnitはモジュール境界、依存方向、配置、禁止API、テスト規約を検査する。
PMDとError Proneはソースまたはコンパイル時に判定する規約を検査する。

## 検査クラス

```text
backend/src/test/java/com/example/demo/architecture/
├── ApplicationModuleArchitectureTest.java
├── ArchitectureRuleFixtureTest.java
├── ClassRoleArchTest.java
├── DateTimeConventionsArchTest.java
├── GeneralCodingRulesArchTest.java
├── PackageByFeatureOnionArchitectureTest.java
├── ProxyRulesArchTest.java
└── TestConventionsArchTest.java
```

## モジュールとパッケージ

`ApplicationModuleArchitectureTest`はSpring Modulithの`ApplicationModules.verify()`を使い、モジュール間の循環、内部パッケージ参照、許可されていない依存を検出する。
モジュール構造の決定は[ADR-001](../adr/ADR-001-adopt-spring-modulith-modular-monolith.md)を参照する。

`PackageByFeatureOnionArchitectureTest`はArchUnitの`Architectures.onionArchitecture()`と追加規則で、パッケージの配置と依存を検査する。
規則名は`@ArchTest`のフィールド名であり、テスト結果にもこの名前で出る。

- `dependenciesPointInward`：`domain.model`、`domain.service`、モジュールルートと`application`、`presentation`、`infrastructure.persistence`、`infrastructure.client`のオニオン構造で依存を内向きに限り、どの層にも属さないパッケージのクラスを拒否する。
- `infrastructureDependsOnlyOnDomainModel`：`infrastructure`は`application`、`domain.service`、モジュールルートの型に依存せず、機能モジュールの型のうち`domain.model`だけを使う。
- `moduleApiDoesNotExposeInternalTypes`：モジュールルートの型は、`java..`、`org.jspecify..`、同じルートパッケージの型だけに依存する。
- `databaseTechnologyApisAreOnlyUsedByPersistenceAdapters`：jOOQ APIと生成型は`infrastructure.persistence`だけで使う。
- `domainModelDoesNotDependOnFrameworks`：`domain.model`はSpring、jOOQ、jOOQの生成型、JPA、Jacksonに依存しない。
- `domainServicesDependOnlyOnDomainAndJava`：`domain.service`は`java..`、`org.jspecify..`、`lombok..`、Domainの型、`@Service`だけに依存する。
- `domainServicesAreAnnotatedWithService`：`domain.service`のトップレベルのクラスに`@Service`を付ける。
- `servicesResideInApplicationOrDomainService`：`@Service`を付けた型は`application`か`domain.service`に置く。
- `controllersResideInPresentationWeb`：`@Controller`と`@RestController`を付けた型は`presentation.web`に置き、名前を`Controller`で終える。
- `repositoriesResideInPersistenceAdapters`：`@Repository`を付けた型は`infrastructure.persistence`に置く。
- `presentationDoesNotDependOnDomain`：`presentation`はDomainに依存しない。
- `domainInterfacesAreImplementedInInfrastructure`：Domainの外で`domain.model`のインタフェースを実装するクラスは`infrastructure`に置く。
- `repositoryImplementationsAreJooqRepositories`：`domain.model`の`*Repository`を実装するクラスは、`infrastructure.persistence`の`Jooq*Repository`にする。
- `externalSystemImplementationsAreClients`：Domainの外で`*Repository`以外の`domain.model`のインタフェースを実装するクラスは、`infrastructure.client`の`*Client`にする。
- `transactionalIsNotDeclaredAtClassLevel`：クラスに`@Transactional`と、それをメタアノテーションに持つアノテーションを付けない。
- `transactionalMethodsArePublicApplicationMethods`：`@Transactional`と、それをメタアノテーションに持つ`@ApplicationModuleListener`を付けたメソッドは、`application`のpublicメソッドに限る。

ベースパッケージ直下の起動クラスと全体設定は、オニオン規則の所属検査から除外する。
パッケージ構造の決定は[ADR-002](../adr/ADR-002-package-by-feature-onion-architecture.md)を、クラスの役割の決定は[ADR-044](../adr/ADR-044-define-backend-class-roles-and-naming.md)を参照する。

## クラスの役割

`ClassRoleArchTest`は、クラスの役割ごとの名前と形を検査する。

- `moduleRootTypesAreRecordsEnumsOrQueries`：モジュールルートの型は、record、enum、`*Queries`インタフェースのどれかにする。
- `applicationServicesHaveRoleNames`：`application`の`@Service`は、`*CommandHandler`、`*QueryService`、`*Listener`のどれかの名前にする。
- `commandHandlersExposeOnlyTransactionalHandle`：`*CommandHandler`のpublicメソッドは、`@Transactional`を付けた`handle`一つだけにし、`*Command`を一つ受け取り`*Result`を返す。
- `commandsAndResultsAreApplicationRecords`：`*Command`と`*Result`は`application`のrecordにする。
- `commandHandlersDoNotDependOnOtherCommandHandlers`：`*CommandHandler`は別の`*CommandHandler`に依存しない。
- `moduleListenersAreApplicationListeners`：`@ApplicationModuleListener`を付けたメソッドは、`application`の`*Listener`の`on`にする。
- `listenersExposeOnlyOnAndCallOneCommandHandler`：`*Listener`のpublicメソッドは`@ApplicationModuleListener`を付けた`void on(...)`一つだけにし、呼ぶ`*CommandHandler`はちょうど一つにする。
- `requestsAndResponsesArePresentationWebRecords`：`*Request`と`*Response`は`presentation.web`のrecordにする。
- `queryServicesImplementModuleQueries`：`*QueryService`は`application`に置いて自モジュールのルートの`*Queries`を実装し、すべてのpublicメソッドに`@Transactional(readOnly = true)`を付ける。

## 規則を確かめるフィクスチャ

プロダクションの規則は`.allowEmptyShould(true)`を付けるため、対象のクラスがなくても成功する。
`ArchitectureRuleFixtureTest`は、テスト専用のフィクスチャで各規則が働くことを確かめる。

- `backend/src/test/java/archfixture/conforming/`：規約どおりの`order`モジュールと`inventory`モジュールの最小の例。すべての規則が誤検出しないことを確かめる。
- `backend/src/test/java/archfixture/violating/`：規則ごとに違反するクラスを置く。各クラスのJavadocに違反する規則名を書く。パラメータ化テストが、規則ごとに対応する違反クラスの完全修飾名を含む失敗を確かめる。

フィクスチャは`com.example.demo`の外に置く。
そのため、Springのコンポーネントスキャン、Spring Modulithの`ApplicationModules`、プロダクション向けの`@AnalyzeClasses(packagesOf = DemoApplication.class)`は、フィクスチャを読まない。
`ArchitectureRuleFixtureTest`は`ClassFileImporter`でフィクスチャを読み込み、ベースパッケージを引数に取る`<規則名>Rule(basePackage)`のファクトリか、規則のフィールドをそのまま使う。

規則を追加するときは、違反フィクスチャのクラスを一つ追加し、`eachRuleDetectsItsViolatingFixture`の行と`rulesFor()`に規則を加える。
この手順で、新しい規則が違反を検出し、規約どおりのコードを誤検出しないことを確かめる。

## 共通実装とプロキシ

`GeneralCodingRulesArchTest`は、標準ストリーム、`java.util.logging`、ロギング実装API、`@Autowired`によるメソッドインジェクションなどの禁止規則を検査する。

`ProxyRulesArchTest`は、次のアノテーションを付けたメソッドへの同一クラス内の直接呼び出しを検査する。

- Springの`@Transactional`、`@Async`、`@Cacheable`、`@CachePut`、`@CacheEvict`。
- Spring Securityの`@PreAuthorize`、`@PostAuthorize`、`@PreFilter`、`@PostFilter`、`@Secured`。
- Resilience4jの`@CircuitBreaker`、`@Retry`、`@RateLimiter`、`@Bulkhead`、`@TimeLimiter`。

## 日時

`DateTimeConventionsArchTest`は、レガシー日時型、引数なしの`now()`、`System.currentTimeMillis()`、`ZoneId.systemDefault()`、許可点以外でのシステム`Clock`生成を検査する。
システム`Clock`の生成は`DemoApplication.clock()`だけを許可する。
日時規約の決定は[ADR-006](../adr/ADR-006-utc-instant-absolute-time-policy.md)を参照する。

Error ProneはすべてのJavaコンパイルで`JavaTimeDefaultTimeZone`と`JavaUtilDate`をerrorとして検査する。

## テストコード

`TestConventionsArchTest`は[TestCodeOnly](../../backend/src/test/java/com/example/demo/architecture/TestCodeOnly.java)で手書きのテストコードを選び、次の内容を検査する。

- `@Test`メソッドに`@DisplayName`がある。
- `@Test`メソッドとテストクラスがpublicではない。
- `@Test`を持つクラス名が`Test`で終わる。
- `@SpringBootTest`を直接付けたクラスが`SharedTestConfiguration`を`@Import`する。
- `assertTimeoutPreemptively`を呼ばない。
- テストコードでレガシー日時型を使わない。
- クラスまたはメソッドの`@Disabled`に理由がある。

コメントとJavadocはバイトコードに残らないため、ArchUnitの検査対象ではない。
テストコードの現行規約は[バックエンドのテストコードの書き方](testing-code-style.md)を参照する。

## PMD

PMDはmainとtestに別のrulesetを適用し、Lombokの`@Data`と`@Setter`、手書きのLoggerフィールド、`LoggerFactory`の直接参照、`getLogger`のstatic import、`@Autowired`を付けた通常メソッドなどを検査する。
PMDの設定は[`ruleset.xml`](../../backend/config/pmd/ruleset.xml)と[`test-ruleset.xml`](../../backend/config/pmd/test-ruleset.xml)を正とする。

## 解析対象と実行

プロダクションコード向けArchUnit検査は[ProductionCodeOnly](../../backend/src/test/java/com/example/demo/architecture/ProductionCodeOnly.java)で手書きコードだけを選び、生成コードとテストコードを除外する。
生成コードを除外しても、手書きコードからjOOQ APIや生成型への依存は検査する。

静的解析は`task be-lint`で、ArchUnitとSpring Modulithの検査は`task test`で実行する。
機能追加後の実行順は[バックエンドの機能追加時の確認](runbook-add-feature.md)を参照する。
