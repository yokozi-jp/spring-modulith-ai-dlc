---
type: Reference
title: バックエンドのアーキテクチャテスト
description: Spring Modulith、ArchUnit、PMD、Error Proneでバックエンド規約を検査する実装、規則名、規則を確かめるフィクスチャ、失敗メッセージの形、解析対象、プロキシの規則が検出しない書き方を示し、規約違反の検出方法を確認するとき、ArchUnitの規則を追加するとき、検査の失敗を直すときに読むリファレンス。
tags: [reference, backend, testing, archunit, spring-modulith]
---

# バックエンドのアーキテクチャテスト

Spring ModulithとArchUnitはモジュール境界、依存方向、配置、禁止API、テスト規約を検査する。
PMDとError Proneはソースまたはコンパイル時に判定する規約を検査する。
DBの規約は、changesetの静的検査と`pg_catalog`のスキーマ検査でも検査する。
ArchUnitとPMDの失敗メッセージは、理由、直し方、規約の文書のパスを示す。

## 検査クラス

```text
backend/src/test/java/com/example/demo/architecture/
├── ApiVersionArchTest.java
├── ApplicationModuleArchitectureTest.java
├── ArchitectureRuleFixtureTest.java
├── ArchitectureRuleMessageTest.java
├── ClassRoleArchTest.java
├── DatabaseConventionsArchTest.java
├── DateTimeConventionsArchTest.java
├── DirectWriteFixtureTest.java
├── GeneralCodingRulesArchTest.java
├── IdempotencyArchTest.java
├── JooqCommonColumnsArchTest.java
├── LambdaFixtureTest.java
├── PackageByFeatureOnionArchitectureTest.java
├── ProxyRulesArchTest.java
├── PublicIdArchTest.java
├── TableWriterArchTest.java
├── TestConventionsArchTest.java
└── ViolatingFixtures.java
```

## モジュールとパッケージ

`ApplicationModuleArchitectureTest`はSpring Modulithの`ApplicationModules.verify()`を使い、モジュール間の循環、内部パッケージ参照、`allowedDependencies`にない依存を検出する。
依存してよいモジュールは各モジュールの`package-info.java`で宣言する（[ADR-118](../adr/ADR-118-declare-module-dependencies-with-allowed-dependencies.md)）。
同じクラスのテストが、OPEN以外のすべてのモジュールが`allowedDependencies`を宣言していることを確かめる。
手書きにした理由はテストのJavadocに書く。
モジュール構造の決定は[ADR-001](../adr/ADR-001-adopt-spring-modulith-modular-monolith.md)を参照する。
jOOQの生成物の`com.example.demo.jooq`はOPENのモジュールであり、`verify()`は生成型への依存を内部の型への依存として扱わない。
`ApplicationModuleArchitectureTest`がこれを確かめる。
他モジュールのテーブルを読む誤りは、jOOQのRepositoryの規約とレビューで防ぐ（[ADR-067](../adr/ADR-067-open-jooq-generated-module.md)）。

`PackageByFeatureOnionArchitectureTest`はArchUnitの`Architectures.onionArchitecture()`と追加規則で、パッケージの配置と依存を検査する。
規則名は`@ArchTest`のフィールド名であり、テスト結果にもこの名前で出る。

- `dependenciesPointInward`：`domain.model`、`domain.service`、モジュールルートと`application`、`presentation`、`infrastructure.persistence`、`infrastructure.client`のオニオン構造で依存を内向きに限り、どの層にも属さないパッケージのクラスを拒否する。
  ベースパッケージ直下と`shared`は、所属検査から除く。`shared.persistence`と`shared.eventresubmission`は、`infrastructure.persistence`と同じ層として検査される。
- `infrastructureDependsOnlyOnDomainModel`：`infrastructure`は`application`、`domain.service`、モジュールルートの型に依存せず、機能モジュールの型のうち`domain.model`だけを使う。
- `sharedModuleIsUsedOnlyByPersistenceAdapters`：`shared`の外で、`shared.persistence`と`shared.eventresubmission`の型に依存するクラスは、`infrastructure.persistence`に置く。
  この規則が使う場所を限るのは、この2つである。使う場所を限る`shared`のパッケージの方針は[共有モジュール shared](shared-module.md)に従い、`shared.batch`の制限は[#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667)で足す。
  `shared`の中の依存は対象にしない（[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）。
- `httpTypesAreNotUsedByInnerLayers`：`shared`と各モジュールの`domain`、`application`、`infrastructure.persistence`は、Spring Web、Springの HTTP、Servletの型に依存しない。HTTPの型を使うのは`presentation.web`、`infrastructure.client`、ベースパッケージ直下の設定に限る意図だが、規則が検査するのは上の4つのパッケージである。
- `httpErrorsAreMappedOnlyInErrorPresentationWeb`：`error.presentation.web`の外のクラスは、`ProblemDetail`、`ErrorResponse`（入れ子の`Builder`と`Interceptor`を含む）、`ErrorResponseException`、`ResponseStatusException`、`@ExceptionHandler`、`@ControllerAdvice`、`@RestControllerAdvice`、`@ResponseStatus`に依存せず、`HttpStatus.CONFLICT`と`HttpStatus.UNPROCESSABLE_CONTENT`を読まない（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
  注釈を付けることも依存に数え、成功の応答の`@ResponseStatus(HttpStatus.CREATED)`も拒否する。
  整数で書いたstatus（`sendError(422)`、`ResponseEntity.status(409)`、`HttpStatusCode.valueOf(422)`）は検出しない。
- `failureReasonsAreModuleEnums`：`shared.failure.FailureReason`を実装する型は、enumにし、ベースパッケージの下のモジュールのパッケージに置く（[ADR-127](../adr/ADR-127-derive-problem-types-from-failure-reasons.md)）。
  フィクスチャは自分の`shared.failure`に同じ名前のinterfaceを持つため、規則は型を名前で指す。
- `moduleApiDoesNotExposeInternalTypes`：モジュールルートの型は、`java..`、`org.jspecify..`、同じルートパッケージの型、イベントが運ぶテナントの`shared.tenancy.TenantId`と`shared.tenancy.TenantScoped`だけに依存する。
  `package-info`は`@ApplicationModule`の宣言を持つため対象から除く。
  違反フィクスチャの`OrderPlacedReadingTenantContext`（`TenantContext`への依存）で確かめる。
- `databaseTechnologyApisAreOnlyUsedByPersistenceAdapters`：jOOQ APIと生成型は`infrastructure.persistence`と、同じ層に入る`shared.persistence`と`shared.eventresubmission`だけで使う。
- `domainModelDependsOnlyOnAllowedTypes`：`domain.model`は、`java.lang..`、`java.util..`、`java.math..`、`java.time..`、`org.jspecify..`、`lombok..`、各モジュールの`domain.model`、`shared.concurrency`、`shared.failure`、`shared.tenancy.TenantId`だけに依存する。`shared.tenancy.TenantContext`は、ScopedValueの暗黙の文脈で引数に現れない入力になるため許さない。
  許可の一覧で止めるため、外部システムのSDKや生成型も、Spring、jOOQ、JPA、Jacksonの型と同じく落ちる。
  Lombokは`lombok..`を許す。
- `applicationDependsOnlyOnAllowedTypes`：`application`は、このアプリケーションの型のほかに、`java..`、`org.jspecify..`、`lombok.Generated`、`org.slf4j..`、`org.springframework.stereotype..`、`org.springframework.transaction.annotation..`、`org.springframework.modulith.events..`、`org.springframework.context`（パッケージ直下）だけに依存する。
- `domainServicesDependOnlyOnDomainAndJava`：`domain.service`は`java.lang..`、`java.util..`、`java.math..`、`java.time..`、`org.jspecify..`、`lombok..`、Domainの型、`shared.failure`の例外、`shared.tenancy.TenantId`、`@Service`だけに依存する。
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

業務のバッチの`<module>.batch`と`shared.batch`（[ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md)）の規則は、まだない。
`batch`を`dependenciesPointInward`の層に足すこと、`shared.batch`を使えるクラスを`batch`に限ること、`batch`の依存方向を検査することは、Jobを足す[#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667)で行う。
`batch`を層に足すまでは、`<module>.batch`のクラスは`dependenciesPointInward`の所属検査で失敗する。

共有モジュール`shared`（[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）のために、ほかの規則へ例外を足していない。
`shared.persistence`と`shared.eventresubmission`はオニオン規則の`persistence`の層に入るため、機能モジュールの`infrastructure.persistence`からの依存は同じ層の中の依存になる。
`infrastructureDependsOnlyOnDomainModel`が拒否するモジュールルートは`com.example.demo.<feature>`の直下だけであり、`shared`のパッケージは含まれない。`shared`が機能モジュールの型に依存しないことは、Spring Modulithの`@ApplicationModule(allowedDependencies = "jooq")`と`ApplicationModuleArchitectureTest`が検査する。
`shared.concurrency`の`VersionedCommand`は`Command`で終わるインタフェースなので、`ClassRoleArchTest`の`commandsAndResultsAreApplicationRecords`の対象から、`VersionedCommand`の完全修飾型名だけを除く。
`shared.concurrency`に置いたほかの`*Command`は、この規則の対象のままである。
`shared`のルートには`package-info.java`だけを置くため、`moduleRootTypesAreRecordsEnumsOrQueries`と`moduleApiDoesNotExposeInternalTypes`に当たる型がない。
パッケージ構造の決定は[ADR-002](../adr/ADR-002-package-by-feature-onion-architecture.md)を、クラスの役割の決定は[ADR-050](../adr/ADR-050-define-backend-class-roles-and-naming.md)を参照する。

## クラスの役割

`ClassRoleArchTest`は、クラスの役割ごとの名前と形を検査する。

- `moduleRootTypesAreRecordsEnumsOrQueries`：モジュールルートの型は、record、enum、`*Queries`インタフェースのどれかにする。
- `applicationServicesHaveRoleNames`：`application`の`@Service`は、`*CommandHandler`、`*QueryService`、`*Listener`のどれかの名前にする。
- `commandHandlersExposeOnlyTransactionalHandle`：`*CommandHandler`のpublicメソッドは、`@Transactional`を付けた`handle`一つだけにし、`*Command`を一つ受け取り`*Result`を返す。
- `commandsAndResultsAreApplicationRecords`：`*Command`と`*Result`は`application`のrecordにする。
- `commandHandlersDoNotDependOnOtherCommandHandlers`：`*CommandHandler`は別の`*CommandHandler`に依存しない。
- `externalSystemCommandHandlersAreDependedOnlyByListeners`：外部システムのインタフェースに直接依存する`application`の`*CommandHandler`は、`application`の`*Listener`からだけ依存される。
  外部システムのインタフェースは、`domain.model`のinterfaceのうち、Repository、アノテーション、`package-info`でないものとする。
  守らせる規約は[CommandHandler](class-roles/command-handler.md)のアンチパターンに示し、理由は[ADR-050](../adr/ADR-050-define-backend-class-roles-and-naming.md)に示す。
- `externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients`：外部システムのインタフェースに依存してよいのは、`application`の`*CommandHandler`と、それを実装する`infrastructure.client`のクラスだけである。
  集約、Domain Service、QueryService、補助のクラスを通して外部システムを呼ぶ経路をなくすため、前の規則は`*CommandHandler`の直接の依存だけを見れば足りる。
- `commandHandlersAndListenersDoNotImplementInterfaces`：`application`の`*CommandHandler`と`*Listener`は、Java標準の型以外のinterfaceを実装しない。
  実装を認めると、呼び出し側がinterfaceだけに依存して`externalSystemCommandHandlersAreDependedOnlyByListeners`と`listenersAreNotCalled`を迂回できる。
- `listenersAreNotCalled`：`application`の`*Listener`に、ほかのクラスは依存しない。
  CommandHandlerやControllerがListenerの`on`を直接呼ぶと、イベント出版レジストリを通らず、失敗しても再投入されず、`externalSystemCommandHandlersAreDependedOnlyByListeners`も迂回できる。
- `eventPublisherIsUsedOnlyByCommandHandlers`：`application`の`*CommandHandler`以外のクラスは、`ApplicationEventPublisher`の`publishEvent`と`ApplicationEventMulticaster`の`multicastEvent`を呼ばない。
  `ApplicationContext`と`ConfigurableApplicationContext`は`ApplicationEventPublisher`を継承するため、同じ判定で検出する。
  `application`の`*CommandHandler`は、`ApplicationContext`越しの発行も含めて検査しない。
- `commandHandlersAndListenersDoNotUseInheritance`：`*CommandHandler`と`*Listener`は、Java標準の型以外のクラスを継承せず、継承も実装もさせない（[ADR-051](../adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。
  違反フィクスチャの`ReopenOrderHandler`（サブクラス）、`RestoreOrderCommandHandler`（基底クラスの`OrderHandlerSupport`）、`OrderShippedReceiver`（インタフェースの実装）で確かめる。
- `moduleListenersAreApplicationListeners`：`@ApplicationModuleListener`を付けたメソッドは、`application`の`*Listener`の`on`にする。
- `moduleListenerEventsAreTenantScoped`：`@ApplicationModuleListener`を付けたメソッドの引数は、すべて`shared.tenancy.TenantScoped`を実装するイベントにする。
  引数が1つであることは、`listenersExposeOnlyOnAndCallOneCommandHandler`と`moduleListenersAreApplicationListeners`が見る。
  `TenantListenerAspect`がイベントのテナントを、リスナーのトランザクションが始まる時点で束縛するためである（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
  違反フィクスチャの`OrderPlacedWithoutTenantListener`で確かめる。
- `tenantIsBoundOnlyByAllowedCallers`：`TenantContext.call`を呼べるクラスは、`tenancy.presentation.web.TenantSelectionFilter`、`shared.tenancy.TenantListenerAspect`、`shared.persistence.IdempotencyKeyPurge`の3つだけにする。
  CommandHandlerやControllerや別のfilterが束縛を切り替えて、認証の権限やイベントに無関係なテナントを操作することを防ぐ。メソッド参照も検出する（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
  違反フィクスチャの`TenantSwitchingCommandHandler`（直接の呼び出し）と`HeaderTenantFilter`（別のモジュールのfilterのメソッド参照）で確かめ、規約どおりのフィクスチャの`TenantSelectionFilter`が通ることを確かめる。
  業務のバッチの`shared.batch`のAspectを許可先に足すのは、Aspectを作る[#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667)である（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
- `unboundTenantIsReadOnlyByTransactionManager`：本番のコードで`TenantContext.findCurrent`を呼べるクラスは、`shared.persistence.ModuleRoleTransactionManager`だけにする。
  ArchUnitは本番のクラスだけを解析するので、テストの補助（`AsTenant`など）は対象の外である。
  `findCurrent`は束縛のないことを空で返すため、業務の処理が使うと、テナントを束縛し忘れた呼び出しをテナントのない処理として通す。
  業務の処理は`TenantContext.current()`で読む。
  メソッド参照も検出する（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)、[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
  違反フィクスチャの`TenantDefaultingCommandHandler`で確かめ、規約どおりのフィクスチャの`ModuleRoleTransactionManager`が通ることを確かめる。
- `roleAndTenantAreSetOnlyByTransactionManager`：本番のコードで`ModuleRoleTransactionManager.setLocal`を呼べるクラスは、`ModuleRoleTransactionManager`だけにする。
  `setLocal`はトランザクションのロールとテナントを、トランザクションの名前と束縛によらずに変えるので、ほかのクラスが呼ぶとモジュールのロールの権限とテナントのRLSを迂回できる。
  テストの補助（`AsModule`）とシーダーは、本番と同じSQLを通すために呼ぶが、ArchUnitの解析対象の外である（[ADR-094](../adr/ADR-094-switch-to-module-database-roles.md)、[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
  違反フィクスチャの`TenantSwitchingOrderAccess`で確かめ、規約どおりのフィクスチャの`ModuleRoleTransactionManager`が通ることを確かめる。
- `eventListenersAreApplicationModuleListeners`：`@ApplicationModuleListener`を付けていないメソッドは、`@EventListener`をメタアノテーションに持たない。
  `@EventListener`と`@TransactionalEventListener`を直接付けた受信を拒否し、`@TransactionalEventListener`をメタアノテーションに持つ`@ApplicationModuleListener`は対象から除く。
  守らせる規約と理由は[Listener](class-roles/listener.md)に示す。
- `applicationListenerIsNotImplemented`：`ApplicationListener`を直接か間接に実装するクラスを拒否する。
  ラムダで作った`ApplicationListener`はクラスファイルにならないため、検出しない。
- `listenersExposeOnlyOnAndCallOneCommandHandler`：`*Listener`のpublicメソッドは`@ApplicationModuleListener`を付けた`void on(...)`一つだけにし、呼ぶ`*CommandHandler`はちょうど一つにする。
- `requestsAndResponsesArePresentationWebRecords`：`*Request`と`*Response`は`presentation.web`のrecordにする。
- `queryServicesImplementModuleQueries`：`*QueryService`は`application`に置いて自モジュールのルートの`*Queries`を実装し、すべてのpublicメソッドに`@Transactional(readOnly = true)`を付ける。
- `queryResultStatusIsNotReadByOtherModules`：モジュールルートのrecordの`status()`と、名前が`Status`で終わるメソッドを、そのモジュールの外から呼ばず、メソッド参照もしない。
  ログに注文の状態を出す`payment.application.ChargeOrderCommandHandler`だけを、完全修飾型名で除く（[ADR-075](../adr/ADR-075-return-state-meaning-in-query-results.md)）。
- `mappingLibrariesAreNotUsed`：MapStruct、ModelMapper、Dozerのパッケージと、jOOQの`DefaultRecordMapper`、`DefaultRecordUnmapper`に依存しない。
- `jooqReflectionMappingIsNotUsed`：名前のリフレクションで列と項目を対応づけるjOOQのメソッドを呼ばない。
  読み取りは、`into`、`intoMap`、`intoGroups`、`fetchMap`、`fetchGroups`と名前が`Into`で終わるメソッドのうち、`Class`を受け取るもの（`into(Class)`、`fetchInto(Class)`、`fetchMap(Field, Class)`など）を検出する。
  書き込みと既存のオブジェクトへの対応づけは、`Record`の`into(Object)`と`from(Object)`、`DSLContext.newRecord(Table, Object)`を検出する。
  `convertFrom`、`Records.mapping`、`into(Table)`、`fetch(RecordMapper)`と、列の型を変換する`intoArray(Field, Class)`、`intoSet(Field, Class)`、`fetch(Field, Class)`は呼んでよい。

## 許可する外部ライブラリの型を足す

`domain.model`と`application`が使える外部ライブラリの型と、`domain.model`と`domain.service`が使える標準型は、`PackageByFeatureOnionArchitectureTest`の`DOMAIN_JAVA_PACKAGES`と`allowedInDomain`（`domain.model`と`domain.service`）、`applicationDependsOnlyOnAllowedTypesRule`の`resideInAnyPackage`（`application`）が持つ。
`@PreAuthorize`のような型を使うときは、次の順で直す。

1. 型を使う層が、その型を使ってよい理由を、層の規約（[layers.md](layers.md)とクラスの役割の「依存してよい型」）に書く。
2. 該当する規則の`resideInAnyPackage`に、必要な最小のパッケージを足す。
3. この節の上の規則の説明を直す。

型を許す理由が書けないときは、型を`infrastructure`の中に閉じる。

## 規則を確かめるフィクスチャ

プロダクションの規則は`.allowEmptyShould(true)`を付けるため、対象のクラスがなくても成功する。
`ArchitectureRuleFixtureTest`、`DirectWriteFixtureTest`、`LambdaFixtureTest`は、テスト専用のフィクスチャで各規則が働くことを確かめる。違反フィクスチャの読み込みは`ViolatingFixtures`で共有する。

- `backend/src/test/java/archfixture/conforming/`：規約どおりの`ordering`モジュール、`inventory`モジュール、`payment`モジュール、`shared`モジュールの最小の例。すべての規則が誤検出しないことを確かめる。
  `ordering`の`JooqOrderRepository`が`shared.persistence`の型を使い、`shared`のルートは`package-info.java`だけを持つ。
- `backend/src/test/java/archfixture/violating/`：規則ごとに違反するクラスを置く。各クラスのJavadocに違反する規則名を書く。パラメータ化テストが、規則ごとに対応する違反クラスの完全修飾名を含む失敗を確かめる。
- `backend/src/test/java/archfixture/eventcontract/`と`backend/src/test/resources/archfixture-events/`：イベントの互換の規則にわざと違反する型と、シナリオごとのfixtureを置く。`EventContractViolationTest`が、各規則が違反を見つけることを確かめる。全件の`EventContractTest`の対象には載せない（[クラスの役割：イベント](class-roles/event.md)の「対応するテスト」）。

フィクスチャは`com.example.demo`の外に置く。
そのため、Springのコンポーネントスキャン、Spring Modulithの`ApplicationModules`、プロダクション向けの`@AnalyzeClasses(packagesOf = DemoApplication.class)`は、フィクスチャを読まない。
`ArchitectureRuleFixtureTest`は`ClassFileImporter`でフィクスチャを読み込み、ベースパッケージを引数に取る`<規則名>Rule(basePackage)`のファクトリか、規則のフィールドをそのまま使う。

`sharedModuleIsUsedOnlyByPersistenceAdapters`は、違反フィクスチャの`application`の`OrderAuditColumns`が`shared.persistence`の型を使うことを検出し、規約どおりのフィクスチャの`JooqOrderRepository`を誤検出しないことを確かめる。

`externalSystemCommandHandlersAreDependedOnlyByListeners`は、違反フィクスチャの`ChargeOrderController`が呼ぶ`ChargeOrderCommandHandler`（`PaymentGateway`に直接依存する）を検出する。
`presentation.web`に置いた名前だけの`ChargeOrderListener`が呼ぶ場合も検出する。
`externalSystemInterfacesAreUsedOnlyByCommandHandlersAndClients`は、違反フィクスチャの`PaymentChargePolicy`（Domain Service）、`PaymentCharger`（`domain.model`）、`PaymentChargerConfig`（`infrastructure.client`に置いた、interfaceを実装しない設定クラス）、`OrderPaymentCharger`（補助のクラス）が`PaymentGateway`に依存することを検出する。
規約どおりのフィクスチャでは、`PaymentGateway`に依存する`CapturePaymentCommandHandler`を`OrderPlacedPaymentListener`だけが呼び、`PaymentGatewayClient`が`PaymentGateway`を実装しても、二つの規則が通ることを確かめる。

`commandHandlersAndListenersDoNotImplementInterfaces`は、違反フィクスチャの`ShelveOrderCommandHandler`が`ShelveOrderUseCase`を、`ChargeTriggerListener`が`ChargeTrigger`を実装することを検出する。
`listenersAreNotCalled`は、違反フィクスチャの`OrderPaidController`が`OrderPaidListener`の`on`を呼ぶことを検出する。

`eventPublisherIsUsedOnlyByCommandHandlers`は、違反フィクスチャの`OrderEventController`（`ApplicationEventPublisher`）、`OrderEventMulticasterController`（`ApplicationEventMulticaster`）、`OrderContextPublisher`（`ApplicationContext`）、`shared.events.OrderEventForwarder`（`shared`に置いた発行を包むクラス）を検出する。
規約どおりのフィクスチャでは、`PlaceOrderCommandHandler`が`ApplicationEventPublisher`で発行しても規則が通ることを確かめる。

`queryResultStatusIsNotReadByOtherModules`は、違反フィクスチャの`inventory.application.OrderStatusGuard`が`ordering`の`OrderSummary`の`status()`を呼ぶことと、`paymentStatus`をメソッド参照することを検出する。
規約どおりのフィクスチャでは、`ordering`の`OrderController`が自モジュールの`OrderSummary::status`を読み、除外する`payment.application.ChargeOrderCommandHandler`が他モジュールの`OrderSummary::status`を読んでも、規則が通ることを確かめる。

`jooqReflectionMappingIsNotUsed`は、違反フィクスチャの`ReflectiveOrderReader`で禁止するメソッドごとに一行を持ち、それぞれの呼び出しを検出することを確かめる。

`tableWritesGoThroughTableWriter`は、違反フィクスチャの`DirectOrderWriter`のメソッドごとに一行を持つ。
`DirectWriteFixtureTest`の`DIRECT_WRITES`が、メソッドと、そのメソッドが検出される禁止の呼び出しを対にして確かめる。
禁止の名前を足したときは、その呼び出しを持つメソッドを`DirectOrderWriter`に足し、`DIRECT_WRITES`に行を足す。
名前が`fetch`で始まる実行は、jOOQ 3.21.9の`Update`と`Delete`に該当するメソッドがなく、行を持たない。
`DirectOrderWriter`は、ラムダの中の`execute()`、`Update`の変数からの`execute()`、`Update::execute`のメソッド参照も含む。
`TableWriterArchTest`の残りの規則は、違反フィクスチャの`JooqOrderRepository`、`UnversionedOrderRepository`、`ApproveOrderCommandHandler`で確かめる。
R1は`ReleaseOrderCommandHandler`（`ReleaseOrderCommand`と`ReleaseOrderRequest`）、`SuspendOrderCommandHandler`（static factoryの呼び出し）、`ResumeOrderCommandHandler`（static factoryのメソッド参照）、`ArchiveOrderCommandHandler`（`save`という別名の書き込み）で確かめる。
R2は`ReopenPolicy`（Domain Serviceの`update`と`save`）、`PurgeOrderQueryService`、`JooqOrderRepository.save(OrderId)`（Repositoryの実装から別のRepositoryの`delete`）で確かめる。
R3は`ForgedLockNoCommandHandler`（コンストラクタと`ExpectedLockNo::new`）と`OrderLockController`で確かめる。
R4は`ApproveOrderCommandHandler`（`handle`から呼ばないprivateメソッドだけが`ensureLockNo`を呼ぶ）と`OverloadedEnsureCommandHandler`で確かめる。
`LambdaEnsureCommandHandler`は、`Optional.ifPresent`のラムダの中の`ensureLockNo`をR4が満たすものとして数えることを`LambdaFixtureTest`で固定する（違反フィクスチャのパッケージにあるが、R4は検出しない）。
規約どおりのフィクスチャの`CancelOrderCommandHandler`は、`Idempotency.execute`で包んだprivateメソッドの中で`ensureLockNo`を呼び、R4と`idempotentCommandHandlersExecuteThroughIdempotency`を満たす。
`unsafeHandlersTakeIdempotencyKey`は、違反フィクスチャの`OrderKeyController`の`place`（キーを受け取らない）と`ship`（本文の後に受け取る）で確かめ、規約どおりのフィクスチャの`OrderController`の`select`（`x-idempotency-key: none`）を除くことを確かめる。
`idempotentCommandHandlersExecuteThroughIdempotency`は、違反フィクスチャの`RedeliverOrderCommandHandler`（`handle`の外でだけ`execute`を呼ぶ）で確かめる。
`commandsAndResultsAreApplicationRecords`の型単位の除外は、`shared.concurrency`のrecordではない`ForceUnlockCommand`で確かめる。
規約どおりのフィクスチャには、`shared.concurrency`と`shared.failure`の型と、Requestが`ExpectedLockNo`を作る`CancelOrderRequest`、Listenerが版のないCommandを作る`OrderPlacedExpiryListener`を置く。
`archfixture.conforming`と`archfixture.violating`の`shared.persistence`には、規則が名前で見る`TableWriter`のスタブを置く。
H3は、違反フィクスチャの`JooqOrderRepository`の`update`と`save`（`TableWriter.update`を呼ばない）と`store`（`TableWriter.update`を呼ぶが`update`という名前ではない）で確かめる。
H6は、`UnversionedOrderRepository`（集約ルートではなく識別子を受け取る）で確かめる。
`allowedUsagesAreNotDetected`の一行は、対応づけの二つの規則が`convertFrom`、`Records.mapping`、`into(Table)`、`fetch(RecordMapper)`、`intoArray(Field, Class)`、`intoSet(Field, Class)`、`fetch(Field, Class)`、`newRecord(Table)`を誤検出しないことを確かめる。
`noSuchElementExceptionIsNotThrown`は、違反フィクスチャの`LegacyOrderFinder`の`notFound`（`NoSuchElementException`の生成）と`firstOrFail`（引数なしの`orElseThrow()`）で確かめる。
規約どおりのフィクスチャの`CancelOrderCommandHandler`、`CancelExpiredOrderCommandHandler`、`ReserveStockCommandHandler`、`OrderController`は`NotFoundException`を、`Order`と`OrderLimitPolicy`は`BusinessRuleViolationException`を投げ、Domain、Application、Presentationから`shared.failure`を使っても規則が通ることを確かめる。
`allowedUsagesAreNotDetected`の別の一行は、`IllegalStateException`、`IllegalArgumentException`、3つの業務上の失敗の例外の送出と`orElseThrow(Supplier)`を、`noSuchElementExceptionIsNotThrown`が誤検出しないことを確かめる。
`httpTypesAreNotUsedByInnerLayers`は、違反フィクスチャの`shared.persistence`の`ChildRowWriter`と`application`の`HttpStatusCommandHandler`が`ResponseStatusException`を投げること、`infrastructure.persistence`の`HttpStatusOrderReader`が`HttpStatus`を使うことを検出する。
`httpErrorsAreMappedOnlyInErrorPresentationWeb`は、違反フィクスチャの`presentation.web`の`OrderErrorAdvice`（`@RestControllerAdvice`、`@ExceptionHandler`、`@ResponseStatus`、`ProblemDetail`の戻り値）、`OrderStatusAdvice`（`@ControllerAdvice`）、`OrderRejectedException`（`ErrorResponseException`の継承）、`OrderResponseStatuses`（`ErrorResponse.builder`、`HttpStatus.UNPROCESSABLE_CONTENT`と`HttpStatus.CONFLICT`の読み出し、`@ResponseStatus(HttpStatus.CREATED)`、値を省いた`@ResponseStatus`）と、`HttpStatusCommandHandler`（`ResponseStatusException`の生成）を、型、注釈、定数ごとに1行ずつ検出する。
`failureReasonsAreModuleEnums`は、違反フィクスチャの`ordering.domain.model`の`OrderRejectionReason`（enumでないrecord）とベースパッケージの直下の`RootFailure`を検出し、規約どおりのフィクスチャの`OrderFailure`（定数の本体を持つ定数を含む）を誤検出しないことを確かめる。
`domainModelDependsOnlyOnAllowedTypes`は、違反フィクスチャの`LoggingOrder`（SLF4Jの`Logger`）、`RemoteOrderLookup`（`java.net.http.HttpClient`）、`TenantReadingOrder`（`TenantContext`）を検出する。規約どおりの`OrderTenantPolicy`（Domain Serviceが`TenantId`を使う）は`domainServicesDependOnlyOnDomainAndJava`が通す。
MapStruct、ModelMapper、Dozerはテストのクラスパスにないため、`mappingLibrariesAreNotUsed`のフィクスチャは`DefaultRecordMapper`と`DefaultRecordUnmapper`への依存だけで確かめる。

`TestConventionsArchTest`の`sleepIsNotUsedInTests`は、`SleepRuleFixtureTest`が`archfixture.sleep`のクラスで確かめる。
直接の呼び出しとメソッド参照が検出され、`Thread`と`TimeUnit`以外の`sleep`が検出されないことを固定する。

規則を追加するときは、違反フィクスチャのクラスを一つ追加し、`eachRuleDetectsItsViolatingFixture`の行と`rulesFor()`に規則を加える。
この手順で、新しい規則が違反を検出し、規約どおりのコードを誤検出しないことを確かめる。
注釈の属性の値で判定する規則では、属性を省いて既定値のままにした注釈も違反フィクスチャに置く。
たとえば値を省いた`@Order`は`Ordered.LOWEST_PRECEDENCE`になり、明示した値だけを見る規則を通ってしまう。

## ArchUnitの規則のbecause

ArchUnitの規則には、`.because(...)`で次の形の文を付ける。
ArchUnitは規則の説明の後に`, because`とこの文をつなぎ、違反した箇所と一緒に出す。

```text
<なぜそうあるべきか>。直し方：<具体的な修正>。規約：<リポジトリのルートからのdocs/のパス>
```

- 理由には、docsかADRに書いてある内容だけを書く。
- 「規約：」には規則を定める最も具体的な文書を書き、必要ならADRを「、」で区切って続ける。
- ArchUnitが生成する規則の説明は、`.as(...)`で置き換えない。
- ArchUnitの標準の規則が英語のbecauseを持つときも、その後ろに`.because(...)`でこの形の文を足す。

失敗すると、次のように出る。

```text
Rule 'no classes should be meta-annotated with @Transactional, because 状態を変えるユースケースの処理の順序とトランザクション境界を一か所で決め、一つのユースケースを Command の受け取りから Result の返却まで一つのトランザクションで進めるため。直し方：クラスの @Transactional を外し、Application の public メソッドへ付け直す。規約：docs/backend/layers.md、docs/backend/class-roles/command-handler.md、docs/adr/ADR-050-define-backend-class-roles-and-naming.md' was violated (1 times):
Class <archfixture.violating.ordering.application.ShipOrderCommandHandler> is meta-annotated with @Transactional in (ShipOrderCommandHandler.java:0)
```

`ArchitectureRuleMessageTest`は、`architecture`パッケージで`@ArchTest`を付けた`ArchRule`のフィールドをすべて集め、最後のbecauseがこの形であることと、「規約：」の各パスがリポジトリにあることを確かめる。
`ArchitectureRuleMessageTest`は、`ruleset.xml`と`test-ruleset.xml`で`name`を持つカスタム規則の`message`も読み、同じ形であることと「規約：」の各パスがリポジトリにあることを確かめる。
`@ArchTest`のメソッドと`ArchTests`のフィールドはbecauseを読めないため、このテストが拒否する。
新しい規則にこの形のbecauseかmessageがないと、`task test`が失敗する。
理由がdocsにもADRにもない規則は、先に理由をdocsかADRに書いてから追加する。

## 共通実装とプロキシ

`GeneralCodingRulesArchTest`は、次の規則を検査する。

- `noClassesShouldAccessStandardStreams`：`System.out`、`System.err`、`printStackTrace()`を使わない。
- `noClassesShouldThrowGenericExceptions`：`Throwable`、`Exception`、`RuntimeException`、`Error`を投げない。
- `noSuchElementExceptionIsNotThrown`：`NoSuchElementException`を生成せず、引数なしの`Optional.orElseThrow()`を呼ばない。
  見つからないことは`shared.failure`の`NotFoundException`で表す（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
  `Optional.get()`、`Iterator.next()`、`Optional::orElseThrow`のメソッド参照など、JDKのAPIが中で投げる`NoSuchElementException`は検出しない。
- `noClassesShouldUseJavaUtilLogging`：`java.util.logging`を使わない。
- `featureCodeUsesOnlySlf4jFacade`：ベースパッケージ直下以外のクラスは、Logback、Log4j、Apache Commons LoggingのAPIに依存しない。
- `noClassesShouldUseJodaTime`：Joda-Timeを使わない。
- `noClassesShouldUseFieldInjection`：フィールドに`@Autowired`などの注入アノテーションを付けない。
- `autowiredMethodsAreNotUsed`：メソッドに`@Autowired`を付けない。
- `assertionsShouldHaveDetailMessage`：`assert`文と`new AssertionError()`に失敗の詳細を書く。
- `deprecatedApiShouldNotBeUsed`：`@Deprecated`のAPIを使わない。
- `oldDateAndTimeClassesShouldNotBeUsed`：`java.util.Date`などの旧日時APIを使わない。
- `restClientIsBuiltFromInjectedBuilder`：`RestClient.builder()`、`RestClient.create()`、`ClientHttpRequestFactoryBuilder.jdk()`などの生成を呼ばず、メソッド参照にもしない。Bootの自動構成の`RestClient.Builder`、`ClientHttpRequestFactoryBuilder`、`HttpClientSettings`を注入して使う（[外部システムのClient](class-roles/external-client.md)）。

`ProxyRulesArchTest`は、プロキシ、非同期の実行、スケジューラが黙って働かなくなる書き方を検査する。
規則が守らせる規約と理由は、[バックエンドのJava実装規約](java-coding.md)の「プロキシで動くアノテーション」に示す。
対象のアノテーションは`INTERCEPTED_ANNOTATIONS`が持ち、同じクラスの`interceptedAnnotationsMatchJavaCodingList`が、その規約の一覧と一致することを確かめる。

- `proxiedMethodsAreNotCalledFromSameClass`：`INTERCEPTED_ANNOTATIONS`のアノテーションを、直接かメタアノテーションとして持つメソッドを、同じクラスの中から呼ぶ箇所を拒否する。
  `@ApplicationModuleListener`のような合成アノテーションを付けたメソッドも対象になる。
  ラムダの中の呼び出しと、継承したメソッドの呼び出しも検出する。
  同じクラスの別のインスタンスの呼び出しも、そのインスタンスがプロキシのBeanかを問わず拒否する。
- `proxiedMethodsAreNotPrivateFinalOrStatic`：同じアノテーションを持つメソッドの`private`、`final`、`static`を拒否する。
  失敗の文は、メソッド、修飾子、メソッドに付けたアノテーションの名前を示す。
- `asyncMethodsReturnVoidOrFuture`：`@Async`をメタアノテーションに持つメソッドの戻り値の型を検査する。
- `scheduledMethodsAreNoArgVoidAndNotPrivateOrFinal`：`@Scheduled`と、繰り返した`@Scheduled`をまとめる`@Schedules`を付けたメソッドの、戻り値の型、引数、`private`、`final`を検査する。
  `static`は拒否しない。

- `publicBeanMethodsAreNotFinal`：public で`@Component`をメタアノテーションに持つクラスの、public で`static`でないメソッドの`final`を拒否する。
  Spring Modulithのobservabilityが、モジュールのpublicなBeanをアノテーションの有無によらずプロキシにするためである。
  違反フィクスチャの`ordering.application.OrderReporter`で確かめる。
  規則はBeanのクラス自身が宣言したメソッドだけを見るため、親クラスで宣言した`final`のメソッドは検出しない。

同じクラスの`pointcutsNarrowTargetsWithWithin`は、AspectJのadviceと`@Pointcut`の式（`value`と`pointcut`）に`within(`があることを検査する。
違反フィクスチャの`shared.persistence.SlowPgmCdAspect`で確かめ、規約どおりのフィクスチャの`PgmCdAspect`を誤検出しないことも確かめる。
同じクラスの`aspectsDeclareOrder`は、`@Aspect`のクラスに`Ordered.LOWEST_PRECEDENCE`より小さい値の`@Order`があることを検査する。
違反フィクスチャの`shared.persistence.UnorderedPgmCdAspect`（`@Order`がない）と`LowestOrderPgmCdAspect`（値のない`@Order`）で確かめ、規約どおりのフィクスチャの`PgmCdAspect`を誤検出しないことも確かめる。
同じクラスの`transactionManagementIsNotRedeclared`は、`@EnableTransactionManagement`を宣言するクラスがないことを検査し、`aspectsDeclareOrder`が前提にするトランザクションのadvisorの順序を保つ。
違反フィクスチャの`shared.persistence.TransactionOrderConfiguration`で確かめる。

### プロキシの規則が検出しない書き方

`ProxyRulesArchTest`の規則は、次の書き方を検出しない。
レビューで見る点は、[バックエンドのJava実装規約](java-coding.md)の「レビューで見る点」に示す。

- メソッド参照（`this::archive`）による自己呼び出し。
  `proxiedMethodsAreNotCalledFromSameClass`はArchUnitの`ProxyRules`を使い、メソッドの呼び出しだけを見て、メソッド参照を見ない。
- 内部クラスから外側のクラスのメソッドを呼ぶ自己呼び出し。
  ArchUnitは内部クラスを別のクラスとして扱う。
- クラスに付けた`@Async`、`@Cacheable`、`@CachePut`、`@CacheEvict`、`@Retryable`、`@ConcurrencyLimit`、Spring Security、Resilience4jのアノテーション。
  規則はメソッドに付けたアノテーションだけを見るため、そのクラスのメソッドの自己呼び出し、`private`、`final`、`static`、`@Async`の戻り値を検出しない。
  クラスに付けた`@Transactional`は、`PackageByFeatureOnionArchitectureTest`の`transactionalIsNotDeclaredAtClassLevel`が拒否する。
- `void`の`@Async`が投げる例外の扱い。
  メソッドの中で例外を受けて記録するか、`AsyncUncaughtExceptionHandler`で回復するかは、メソッドの形から分からない。
- 複数のプロキシの順序（`@Retryable`や`@CircuitBreaker`が、トランザクションの内側と外側のどちらで動くか）。
  `aspectsDeclareOrder`は、`@Aspect`のクラスとトランザクションの順序だけを見る。
- `@Transactional`の伝播（`propagation`）とロールバックの条件。
  属性の値が意図どおりかは、メソッドの形から分からない。

## 日時

`DateTimeConventionsArchTest`は、レガシー日時型、`Clock`を受け取らない`now(...)`、`Instant`以外の`now(Clock)`、`System.currentTimeMillis()`、`ZoneId.systemDefault()`、許可点以外でのシステム`Clock`生成と`InstantSource.system()`、jOOQの`DSL`でDBの時計を読む関数を検査する。
システム`Clock`の生成は`DemoApplication.clock()`だけを許可する。
呼び出しに加えて、`Instant::now`のようなメソッド参照も検査する。
日時規約の決定は[ADR-006](../adr/ADR-006-utc-instant-absolute-time-policy.md)と[ADR-046](../adr/ADR-046-derive-local-dates-with-configured-business-zone.md)を参照する。

Error ProneはすべてのJavaコンパイルで`JavaTimeDefaultTimeZone`と`JavaUtilDate`をerrorとして検査する。

## 共通カラム

`JooqCommonColumnsArchTest`は、jOOQの生成クラスの`CREATED_*`、`UPDATED_*`、`PATCHED_*`フィールドを参照するクラスが、`com.example.demo.shared.persistence`の外にないことを検査する。
INSERTとUPDATEの共通カラムは`TableWriter`が書く。Repositoryは`CommonColumns`を使わない。
`CommonColumns.forInsert`はテストのデータ作成のために公開しているが、本番のコードは`insertInto`を`TableWriter`の外で呼べない（H1）。
`LOCK_NO`は各モジュールが楽観的ロックの版を読むため対象外にし、書き込みは`ColumnValues`が実行時に拒否する。
生成したRecordのgetterは検査しない。
共通カラムの扱いは[PostgreSQLの共通カラム](../database/postgresql-common-columns.md)、sharedモジュールの決定は[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)を参照する。

## 書き込みの入口

`TableWriterArchTest`は、業務テーブルのINSERTとUPDATEを`shared`の`TableWriter`に集める規則を検査する（[PostgreSQLの排他制御](../database/postgresql-concurrency-control.md)、[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
集約ルートは、`domain.model`にあり、`shared.concurrency`の`Versioned`を実装する型とする。

- `tableWritesGoThroughTableWriter`：`TableWriter`と、その子の行の書き込み（`ChildWriter`）、`Idempotency-Key`の行の書き込み（`IdempotencyKeyWriter`）、古い出版を`FAILED`に戻す更新（`StalePublications`）の外の本番のコードは、次のAPIを呼ばず、メソッド参照もしない。
  呼び出し先の型が、挙げた型に代入できるかで判定する。
  - `DSLContext`、`DSL`、`WithStep`の`insertInto`、`insertQuery`、`update`、`delete`、`deleteFrom`、`mergeInto`、`updateQuery`、`deleteQuery`
  - `DSLContext`の`batchInsert`、`executeInsert`、`loadInto`、`batchUpdate`、`batchStore`、`batchDelete`、`batchMerge`、`executeUpdate`、`executeDelete`、`connection`、`connectionResult`
  - `Update`と`Delete`の`execute`、`executeAsync`、`returning`、`returningResult`と、名前が`fetch`で始まるメソッド
  - `Query`、`RowCountQuery`、`DMLQuery`の同じ実行（メソッド参照のバイトコードには`execute`を宣言した`Query`が残るため）
  - `Merge`のすべてのメソッド
  - `InsertOnDuplicateStep`と`InsertQuery`の`onConflict`、`onConflictOnConstraint`、`onConflictWhere`、`onDuplicateKeyUpdate`、`addValueForUpdate`、`addValuesForUpdate`と、`LoaderOptionsStep`の`onDuplicateKeyUpdate`
  - `TableRecord`と`UpdatableRecord`の`insert`、`store`、`update`、`delete`、`merge`と、`DAO`の`insert`、`update`、`delete`、`deleteById`、`merge`
  - `DataSource.getConnection`、`ConnectionProvider.acquire`、`Connection`と`Statement`のすべてのメソッド
  - `org.jooq`のパッケージの型の、名前が`$`で始まるメソッド（`QOM`の問い合わせのモデルのAPIは、`$onDuplicateKeyUpdate`でINSERTをUPSERTに組み替えられるため）
  - Spring JDBCの`org.springframework.jdbc`とそのサブパッケージの型（`ResourceDatabasePopulator`と`ScriptUtils`も任意のSQLを流せるため）
  - `DataSource`、`Connection`、`ConnectionProvider`に代入できる型を引数に取るメソッドとコンストラクタ（Spring Bootの`DataSourceScriptDatabaseInitializer`や`DSL.using(Connection)`のように、接続の元を受け取るライブラリは、パッケージを選ばずに任意のSQLを流せるため）

  `Insert`、`Update`、`Delete`を作る入口をすべて禁じるため、`batch`や`subscribe`のように作った問い合わせを受け取って実行するAPIは禁じない。

  `tableWritesGoThroughTableWriter`が除外するのは、`TableWriter`、`ChildWriter`、`IdempotencyKeyWriter`と、トランザクションの初めにモジュールのロールへ切り替える`ModuleRoleTransactionManager`とその`Config`、落ちたまま残った出版を`FAILED`に戻す`shared.eventresubmission`の`StalePublications`の完全修飾型名だけである。
  `StalePublications`は業務テーブルに触れず、Spring Modulithの`modulith.event_publication`を1文のUPDATEで更新する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
  `ModuleRoleTransactionManager`は業務テーブルに触れず、ロールとテナントを設定する`set_config`だけを実行する（[ADR-094](../adr/ADR-094-switch-to-module-database-roles.md)、[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
  除外を足す、外す、または除外する型を移すときは、先に[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)の判断を変える。
  同じ変更で、`tableWritesGoThroughTableWriterRule`の完全修飾型名と、除外する型を置く適合フィクスチャ（`archfixture/conforming/shared/persistence/`）を更新する。
  禁止するAPIの違反フィクスチャ（`DirectOrderWriter`）は、禁止の一覧か`isDirectWrite`の判定を変えるときだけ更新する。
- `repositoryUpdateCallsVersionedEntryPoint`：`Jooq*Repository`の、集約ルートを受け取る`add`以外のpublicメソッドは、`update`という名前で、`TableWriter.update`をそのメソッドの中で直接呼ぶ。
  名前で対象を選ばないため、`save`のような名前のメソッドも検出する。
  `TableWriter`には`delete`の入口がないため、`delete`も検出する。必要になったら[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)を更新して入口を足す。
  期待する版は`TableWriter.update`が、受け取った集約ルート（`Versioned`）の`lockNo()`から読むため、`lockNo()`の呼び出しは求めない。
  テーブルから読み直した版を期待する版に渡す書き方は、`AggregateTable`の型引数`A`に集約ルートのクラスを指定すれば、型で書けない（`Versioned`そのものを指定してラムダを渡す書き方と、読み直した版で集約ルートを作り直して渡す書き方は、レビューで見る）。
- `repositoryWritesTakeVersionedAggregates`：`domain.model`の`*Repository`インタフェースの`add`、`update`、`delete`は、集約ルートを一つだけ受け取る。
  集約ルートが`Versioned`を実装しないと、ほかの規則が集約ルートを見つけられず空のまま通るため、この規則で形を確かめる。
  必須の`add`を対象に含めるため、`save`のような名前で保存するRepositoryでも、`Versioned`を実装しない集約は`add`で検出される。
- R1とR2は、`domain.model`の`*Repository`の`add`以外の書き込みを同じ判定で見つける。
  書き込みは、`update`、`delete`と、集約ルートを受け取るメソッドである。
  `repositoryUpdateCallsVersionedEntryPoint`と同じく集約ルートの引数で選ぶため、`save`のような別名でも規則を外れない。
- `commandsBuiltByPresentationForWritesAreVersioned`（R1）：`.presentation.`のクラスが作ったCommandを`handle`で受け取り、Repositoryの`add`以外の書き込みを呼ぶ`*CommandHandler`は、そのCommandが`VersionedCommand`を実装する。
  作るとは、コンストラクタの呼び出し、コンストラクタ参照、Command自身を返すstatic factoryの呼び出しとメソッド参照のどれかである。
  RequestとListenerの両方が作るCommandは、Request用とListener用に分ける。
  Listenerが作るCommandは`VersionedCommand`でなくてよい。
- `onlyCommandHandlersUpdateOrDeleteAggregates`（R2）：`*CommandHandler`以外のクラスは、Repositoryの`add`以外の書き込みを呼ばず、メソッド参照もしない。
  Domain Service、QueryService、Repositoryの実装からの保存を止める。
  Repositoryの実装から別のRepositoryの書き込みを呼ぶ経路も止める。
- `expectedLockNoIsCreatedOnlyByRequests`（R3）：`presentation.web`の`*Request`以外のクラスは、`ExpectedLockNo`のコンストラクタを呼ばず、`ExpectedLockNo::new`も使わない。
  Repository、`TableWriter`、CommandHandler、Controllerが読んだ版から作ることを止める。
- `commandHandlersEnsureScreenLockNo`（R4）：`*CommandHandler`の、引数のCommandが`VersionedCommand`である`handle`は、その中か、そこから呼ぶ同じクラスのprivateメソッドの中で直接`domain.model`の型の`ensureLockNo`で、引数に`ExpectedLockNo`を持つものを呼ぶ。
  privateメソッドの中も数えるのは、`Idempotency.execute`で包んだ本体をprivateメソッドに置くためである（[ADR-126](../adr/ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md)）。
  `handle`から呼ばないメソッドの中の呼び出しと、`ensureLockNo(long)`のオーバーロードでは満たせない。
  ラムダの本体の呼び出しは`handle`の呼び出しとして数えるため、実行されないことがあるラムダの中でも満たす。
  この扱いは`LambdaFixtureTest`が固定する。
  別の集約のインスタンスに呼ぶ、業務の検査の後に呼ぶ、`command.expectedLockNo()`以外の値を渡す書き方は検査しない（値の流れは追わない）。
- `jooqVersionIsReviewed`：実行時のjOOQの版が、禁止の一覧を見直した版と一致する。
  版が変わったら[jOOQのSQLの書き方](../database/jooq-usage.md)の「jOOQの版を上げるとき」に従う。

`IdempotencyArchTest`は、GETとHEAD以外の業務のAPIが`Idempotency-Key`を受け取り、保存したResultを返す入口を通ることを検査する（[ADR-126](../adr/ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md)）。

- `unsafeHandlersTakeIdempotencyKey`：`presentation.web`の、`@PostMapping`、`@PutMapping`、`@PatchMapping`、`@DeleteMapping`を付けたメソッドは、`@PathVariable`の引数の次の引数で`shared.idempotency.IdempotencyKey`を受ける。
  `@Operation`の拡張に`x-idempotency-key: none`を付けたメソッドは除く。
  `@RequestMapping(method = ...)`で書いたマッピングは検出しない。
- `idempotentCommandHandlersExecuteThroughIdempotency`：`*CommandHandler`の、引数のCommandが`IdempotentCommand`である`handle`は、その中で`Idempotency.execute`を呼ぶ。

`ApiVersionArchTest`は、`/api/`のControllerがAPIのバージョンを宣言することを検査する（[ADR-138](../adr/ADR-138-introduce-api-v1-with-spring-api-versioning.md)）。

- `apiControllersDeclareVersion`：`presentation.web`の`@RestController`のうち、クラスの`@RequestMapping`のパスが`/api/`で始まるものは、パスを`/api/v{version}/`で始め、`version`を宣言する。
  `version`を書かないmappingはどのバージョンにも一致し、パスに`v{version}`がないとバージョンを読む区間がずれる。
  メソッドの`@RequestMapping`に書いた`/api/`のパスは検出しない。

## DB

`DatabaseConventionsArchTest`は、次の規則を検査する。
`Jooq<Aggregate>Repository`の作成時の点検は[jOOQのRepository](class-roles/jooq-repository.md)のチェックリストに示す。

- `transactionIsolationIsNotDeclared`：Springの`@Transactional`をクラスまたはメソッドに付けるとき、`isolation`を指定しない。
- `plainSqlApisAreNotUsed`：`@PlainSQL`の付いたjOOQのAPIを呼び出さず、メソッド参照もしない。
- `verbatimSqlApisAreNotUsed`：`DSL.unquotedName`と`DSL.keyword`を呼び出さず、メソッド参照もしない。どちらも`@PlainSQL`を持たないが、文字列をそのままSQLに出力する。
- `nameBuildingApisAreNotUsed`：識別子の`Name`を作る`DSL.name`、`DSL.quotedName`、`DSL.systemName`、`Name.append`を、引数の型によらず呼び出さず、メソッド参照もしない。理由と規則の範囲は[jOOQのSQLの書き方](../database/jooq-usage.md)に示す。
- `renderSchemaIsNotChanged`：`Settings.withRenderSchema`と`Settings.setRenderSchema`を呼び出さず、メソッド参照もしない。引数の値はバイトコードに残らないため、`withRenderSchema(false)`の禁止を呼び出しの禁止で近似する。
- `statementTypeAndParamTypeAreNotChanged`：`Settings`の`withStatementType`、`setStatementType`、`withParamType`、`setParamType`を呼び出さず、メソッド参照もしない。
- `persistenceAdaptersUseOnlyOwnGeneratedSchema`：`<module>.infrastructure.persistence`は、自分のモジュールのスキーマのjOOQの生成クラス（`jooq.<module>..`）だけを使う。別のモジュールのスキーマと`modulith`の生成クラスは使わない。モジュールのロールは自分のスキーマ以外を書けないため、実行時の権限エラーを静的に先取りする。生成クラスを`infrastructure.persistence`の外で使わないことは、`PackageByFeatureOnionArchitectureTest`の`databaseTechnologyApisAreOnlyUsedByPersistenceAdapters`が止める。
- `moduleCodeDoesNotUseJdbc`：機能モジュールのクラスは、`org.springframework.jdbc..`（`JdbcTemplate`など）、`java.sql..`、`javax.sql..`を使わない。トランザクションの初めに切り替えたロールを迂回する経路を止める。
- `moduleCodeDoesNotChangeConnectionSettings`：機能モジュールのクラスは、`DSLContext`の`set`、`setLocal`、`setSchema`、`setCatalog`、`connection`、`connectionResult`と、`DSL`の`set`、`setLocal`、`setSchema`、`setCatalog`を呼び出さない。`SET ROLE`で切り替えたロールを書き換えられるためである（[ADR-094](../adr/ADR-094-switch-to-module-database-roles.md)）。

機能モジュールは`ModuleRoles.MODULES`の名前で決まる。
`ModuleRoles.MODULES`が`modulith`を除くjOOQのスキーマと一致し、どれもSpring Modulithのモジュールであることは、`ModuleRolesTest`が確かめる。
`DSL.name`での表の指定の禁止は、上の`nameBuildingApisAreNotUsed`が全プロダクションコードに適用する。

`PublicIdArchTest`は、主キーをモジュールの外へ出さない規則を検査する（[PostgreSQLの主キー](../database/postgresql-primary-keys.md)）。

- `numericIdsDoNotLeaveModules`：モジュールの外に出る型は、数値のIDのフィールド、引数、戻り値を持たない。
  対象の型の範囲と、数値のIDとみなす名前と型は、[PostgreSQLの主キー](../database/postgresql-primary-keys.md#使い分けの検査)の「使い分けの検査」に示す。
  ArchUnitは引数の名前を読まないため、コンパイラの`-parameters`が残した名前をリフレクションで読む。
  違反フィクスチャのイベント、`*Queries`の引数と戻り値（`List<Long>`、`Optional<Long>`、`long[]`を含む）、Domain、Command、`*Client`の引数、`shared.concurrency`のrecordで確かめる。

DBの規約のほかの検査は`backend/src/test/java/com/example/demo/persistence/conventions/`に置く。

- `ChangesetLintTest`は、Liquibaseのchangesetを、DBに接続せずに静的に検査する。changesetを順に適用してテーブルの最終形を組み立てる。違反を含むフィクスチャは`backend/src/test/resources/conventions/changeset-lint/`に置く。
- `SchemaConventionsTest`は、`pg_catalog`の行から違反を判定する関数を、違反を含む行で検証する。
- `SchemaInspectionTest`は、マイグレーション後のテストDBの`pg_catalog`をアプリロールで読み、同じ関数で検査する。
  モジュールのロールは、Spring Modulithが見つけた機能モジュールの直接の依存先と照らして検査する。
  DBのテーブルが[保持期限と個人データの一覧](../database/table-retention-and-personal-data.md)に載っているかも検査する。

権限の規則は次のとおりである。根拠は[DB接続情報とロール分離](../database/connections.md)にある。

- G1：アプリロールは、`modulith`以外のスキーマのUSAGEと、`modulith`以外の表への権限を持たない。DDLの属性も持たない。
- G3：権限のSQL（`GRANT`、`REVOKE`、`ALTER DEFAULT PRIVILEGES`）は、`ChangesetPrivilegeRules`が判定する。`PUBLIC`、`WITH GRANT OPTION`、DML以外の権限、スキーマを指定しない`ALTER DEFAULT PRIVILEGES`、モジュールのロール（`<module>_module`）とアプリロールのプレースホルダ以外の付与先を拒否する。012以降のchangesetは、業務のスキーマの権限をアプリロールに与えない。権限のSQLだけのchangesetは、任意のSQL（M17）の許可リストに載せない。
- G4：モジュールのロールの一覧が`ModuleRoles.MODULES`と一致し、ログインできず、DDLの属性を持たず、アプリロールが継承なしで切り替えられる。
- G5：モジュールのロールの権限が、自分のスキーマのDML、依存の方向のSELECT、`modulith.event_publication`へのINSERTだけで、期待する集合と完全に一致する。

テナントと外部キーの規則は次のとおりである。
根拠は[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md)と[PostgreSQLの制約とインデックス](../database/postgresql-constraints-and-indexes.md#外部キー)にある。
テナントで分ける業務テーブルの範囲は[RLSでテナントを分ける業務テーブル](../database/postgresql-multitenancy.md#rlsでテナントを分ける業務テーブル)に示す。

- S1：changesetのRLSとポリシーのSQLは、テナントで分ける業務テーブルへの決まった3文（`ENABLE`、`FORCE`、`tenant_isolation`のポリシーの作成）だけにする。
  `missing_ok`を渡すポリシー、`PUBLIC`以外を対象にするポリシー、テナントで分ける業務テーブルの外の表へのRLS、ほかの形の文を、rollbackを含めて拒否する。
  3文だけのSQLは、任意のSQL（M17）の許可リストに載せない。
- S2：changesetでRLSの文を書いた表には、3文をすべて同じchangesetに書く。
- S3：カタログのテナントで分ける業務テーブルに、`ENABLE`と`FORCE`のRLSが付いている。
- S4：カタログのテナントで分ける業務テーブルが、uuidのNOT NULLで、既定値がトランザクションのテナントの`tenant_public_id`を持つ。
- S5：カタログのテナントで分ける業務テーブルのポリシーが、`PERMISSIVE`、`FOR ALL`、`TO PUBLIC`、`missing_ok`を使わない`USING`の式、`WITH CHECK`なしの1つだけである。
- S6：カタログのテナントで分ける業務テーブルの、主キー以外のユニークインデックスが`tenant_public_id`から始まる。
- K1：カタログの同じスキーマのほかの表の主キーと同じ名前の列に、外部キーがある。
  外部キーは、テナントで分ける業務テーブルなら`(tenant_public_id, 参照先の主キー)`、`tenancy`の表なら参照先の主キーだけの形にし、形の違う外部キーも拒否する。
  外部キーの列は、すべてNOT NULLにする。
- K4：changesetの外部キーは`addForeignKeyConstraint`だけで作り、名前を`fk_{表}`か`fk_{連番}_{表}`にし、同じスキーマの表をK1と同じ列で参照し、`onDelete`、`onUpdate`、`deleteCascade`を付けない。

表と列の論理名の規則は次のとおりである。
根拠は[PostgreSQLの命名規約](../database/postgresql-naming.md#論理名のコメント)にある。

- N12：カタログの業務テーブルと、そのカラムに、空白だけでないコメントがある。
  ビューと、パーティションの子テーブル（`relispartition`）とそのカラムは対象外にする。
  PostgreSQLは、親のパーティションテーブルのコメントを子へ写さないためである。
  違反のメッセージは、表と列のそれぞれの直し方を示す。

スキーマ検査の規則を足すときは、判定に要る行を`CatalogSnapshot`に足して`SchemaCatalog.read`で埋め、判定する関数を`SchemaConventions.check`から呼ぶ。

規約の例外は、理由を付けて`ChangesetLint.ALLOWLIST`と`SchemaConventions.ALLOWLIST`に載せる。
何も抑止しない項目は違反として報告する。
changesetの検査は`modulith`スキーマを、スキーマ検査は`modulith`と`liquibase`のスキーマを、業務テーブルの規則から除く。

## テストコード

`TestConventionsArchTest`は[TestCodeOnly](../../backend/src/test/java/com/example/demo/architecture/TestCodeOnly.java)で手書きのテストコードを選び、次の内容を検査する。

- `@Test`メソッドに`@DisplayName`がある。
- `@Test`メソッドとテストクラスがpublicではない。
- `@Test`を持つクラス名が`Test`で終わる。
- `@SpringBootTest`を直接付けたクラスが`SharedTestConfiguration`を`@Import`する。
- `assertTimeoutPreemptively`を呼ばない。
- `Thread.sleep`と`TimeUnit.sleep`を、呼び出しでもメソッド参照でも使わない。同名の別のメソッドは対象にしない。
- テストコードでレガシー日時型を使わない。
- クラスまたはメソッドの`@Disabled`に理由がある。

コメントとJavadocはバイトコードに残らないため、ArchUnitの検査対象ではない。
テストコードの現行規約は[バックエンドのテストコードの書き方](testing-code-style.md)を参照する。

## PMD

PMDはmainに`ruleset.xml`を、testに`ruleset.xml`と`test-ruleset.xml`の両方を適用し、Lombokの`@Data`と`@Setter`、手書きのLoggerフィールド、`LoggerFactory`の直接参照、`getLogger`のstatic import、`@Autowired`を付けた通常メソッド、enum、型パターン、recordパターンのswitchの`default`（`NoDefaultInPatternOrEnumSwitch`）などを検査する。
PMDの設定は[`ruleset.xml`](../../backend/config/pmd/ruleset.xml)と[`test-ruleset.xml`](../../backend/config/pmd/test-ruleset.xml)を正とする。
カスタム規則の`message`は、ArchUnitのbecauseと同じ「理由。直し方：…。規約：…」の形にする。
`ArchitectureRuleMessageTest`が、この形と「規約：」のパスの実在を確かめる。

## 失敗したときの読み方と直し方

Spring Modulith、Error Prone、NullAway、SpotBugs、Spotlessの失敗の文は変えられないため、次の表で読む。

| ツール          | 典型的な失敗の文                                                                                     | 意味                                                                                          | 直し方                                                              | 文書                                                                             |
| --------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Spring Modulith | `Module 'ordering' depends on non-exposed type ... within module 'inventory'!`                       | 他モジュールの内部パッケージの型を使っている                                                  | 相手のルートの`<Feature>Queries`かイベントで連携する                | [バックエンドアーキテクチャ](architecture.md)                                    |
| Error Prone     | `error: [JavaTimeDefaultTimeZone] LocalDate.now() is not allowed ...`                                | 角括弧の中がチェック名で、`(see https://errorprone.info/bugpattern/<チェック名>)`に説明がある | 日時のチェックは、注入した`Clock`と設定値の`ZoneId`で求める形に直す | [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)                  |
| NullAway        | `error: [NullAway] dereferenced expression 's' is @Nullable`                                         | `@NullMarked`のコードで、`@Nullable`の値をnullを確かめずに使った                              | nullを確かめてから使うか、値を必ず渡して`@Nullable`を外す           | [NullAway, Error Messages](https://github.com/uber/NullAway/wiki/Error-Messages) |
| SpotBugs        | `Verification failed: SpotBugs ended with exit code 1. See the report at: ...`                       | バイトコードからバグのパターンを検出した                                                      | レポートのバグのパターンの説明と行を見て直す                        | [Lintとテストのリファレンス](../tooling/lint-and-test.md)                        |
| Spotless        | `The following files had format violations:`、`Run './gradlew spotlessApply' to fix all violations.` | Google Java Formatの形と違う                                                                  | `task be-format`を実行する                                          | [Lintとテストのリファレンス](../tooling/lint-and-test.md)                        |

## 解析対象と実行

プロダクションコード向けArchUnit検査は[ProductionCodeOnly](../../backend/src/test/java/com/example/demo/architecture/ProductionCodeOnly.java)で手書きコードだけを選び、生成コードとテストコードを除外する。
除外する生成コードは、jOOQの生成先である基底パッケージ直下の`jooq`パッケージだけとする。
`ordering.infrastructure.persistence.jooq`のような手書きのパッケージまで除外すると、そこに置いたRepositoryが`tableWritesGoThroughTableWriter`を外れるためである。
除外はクラスの場所で判定するため、基底パッケージ直下の`jooq`パッケージにはjOOQのコード生成の出力だけを置く。
そこに置いた手書きのクラスは、ソースのディレクトリを問わず、すべてのプロダクションコード向けの規則を外れるためである。
`ProductionCodeOnlyTest`がこの境界を確かめ、そのパッケージの本番のクラスのソースファイルがすべて`src/generated/jooq`にあることも確かめる。
生成コードの基準の型には、スキーマの数によらずルートに残る`DefaultCatalog`を使う。
生成コードを除外しても、手書きコードからjOOQ APIや生成型への依存は検査する。

静的解析は`task be-lint`で、ArchUnitとSpring Modulithの検査は`task test`で実行する。
機能追加後の実行順は[バックエンドの機能追加時の確認](runbook-add-feature.md)を参照する。
