---
type: Reference
title: バックエンドのアーキテクチャテスト
description: Spring Modulith、ArchUnit、PMD、Error Proneでバックエンド規約を検査する実装、規則名、規則を確かめるフィクスチャ、失敗メッセージの形、解析対象を示し、規約違反の検出方法を確認するとき、ArchUnitの規則を追加するとき、検査の失敗を直すときに読むリファレンス。
tags: [reference, backend, testing, archunit, spring-modulith]
---

# バックエンドのアーキテクチャテスト

Spring ModulithとArchUnitはモジュール境界、依存方向、配置、禁止API、テスト規約を検査する。
PMDとError Proneはソースまたはコンパイル時に判定する規約を検査する。
ArchUnitとPMDの失敗メッセージは、理由、直し方、規約の文書のパスを示す。

## 検査クラス

```text
backend/src/test/java/com/example/demo/architecture/
├── ApplicationModuleArchitectureTest.java
├── ArchitectureRuleFixtureTest.java
├── ArchitectureRuleMessageTest.java
├── ClassRoleArchTest.java
├── DateTimeConventionsArchTest.java
├── GeneralCodingRulesArchTest.java
├── JooqCommonColumnsArchTest.java
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
- `sharedModuleIsUsedOnlyByPersistenceAdapters`：`shared`の外で`shared`の型に依存するクラスは、`infrastructure.persistence`に置く。
  `shared`の中の依存は対象にしない。
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

共有モジュール`shared`（[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)）のために、ほかの規則へ例外を足していない。
`shared.infrastructure.persistence`はオニオン規則の`persistence`の層に入るため、機能モジュールの`infrastructure.persistence`からの依存は同じ層の中の依存になる。
`infrastructureDependsOnlyOnDomainModel`が拒否するモジュールルートは`com.example.demo.<feature>`の直下だけであり、`shared.infrastructure.persistence`は含まれない。
`shared`のルートには`package-info.java`だけを置くため、`moduleRootTypesAreRecordsEnumsOrQueries`と`moduleApiDoesNotExposeInternalTypes`に当たる型がない。
パッケージ構造の決定は[ADR-002](../adr/ADR-002-package-by-feature-onion-architecture.md)を、クラスの役割の決定は[ADR-050](../adr/ADR-050-define-backend-class-roles-and-naming.md)を参照する。

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
- `mappingLibrariesAreNotUsed`：MapStruct、ModelMapper、Dozerのパッケージと、jOOQの`DefaultRecordMapper`、`DefaultRecordUnmapper`に依存しない。
- `jooqReflectionMappingIsNotUsed`：名前のリフレクションで列と項目を対応づけるjOOQのメソッドを呼ばない。
  読み取りは、`into`、`intoMap`、`intoGroups`、`fetchMap`、`fetchGroups`と名前が`Into`で終わるメソッドのうち、`Class`を受け取るもの（`into(Class)`、`fetchInto(Class)`、`fetchMap(Field, Class)`など）を検出する。
  書き込みと既存のオブジェクトへの対応づけは、`Record`の`into(Object)`と`from(Object)`、`DSLContext.newRecord(Table, Object)`を検出する。
  `convertFrom`、`Records.mapping`、`into(Table)`、`fetch(RecordMapper)`と、列の型を変換する`intoArray(Field, Class)`、`intoSet(Field, Class)`、`fetch(Field, Class)`は呼んでよい。

## 規則を確かめるフィクスチャ

プロダクションの規則は`.allowEmptyShould(true)`を付けるため、対象のクラスがなくても成功する。
`ArchitectureRuleFixtureTest`は、テスト専用のフィクスチャで各規則が働くことを確かめる。

- `backend/src/test/java/archfixture/conforming/`：規約どおりの`order`モジュール、`inventory`モジュール、`shared`モジュールの最小の例。すべての規則が誤検出しないことを確かめる。
  `order`の`JooqOrderRepository`が`shared.infrastructure.persistence`の型を使い、`shared`のルートは`package-info.java`だけを持つ。
- `backend/src/test/java/archfixture/violating/`：規則ごとに違反するクラスを置く。各クラスのJavadocに違反する規則名を書く。パラメータ化テストが、規則ごとに対応する違反クラスの完全修飾名を含む失敗を確かめる。

フィクスチャは`com.example.demo`の外に置く。
そのため、Springのコンポーネントスキャン、Spring Modulithの`ApplicationModules`、プロダクション向けの`@AnalyzeClasses(packagesOf = DemoApplication.class)`は、フィクスチャを読まない。
`ArchitectureRuleFixtureTest`は`ClassFileImporter`でフィクスチャを読み込み、ベースパッケージを引数に取る`<規則名>Rule(basePackage)`のファクトリか、規則のフィールドをそのまま使う。

`sharedModuleIsUsedOnlyByPersistenceAdapters`は、違反フィクスチャの`application`の`OrderAuditColumns`が`shared.infrastructure.persistence`の型を使うことを検出し、規約どおりのフィクスチャの`JooqOrderRepository`を誤検出しないことを確かめる。

`jooqReflectionMappingIsNotUsed`は、違反フィクスチャの`ReflectiveOrderReader`で禁止するメソッドごとに一行を持ち、それぞれの呼び出しを検出することを確かめる。
`typeSafeJooqMappingIsAllowed`は、対応づけの二つの規則が`convertFrom`、`Records.mapping`、`into(Table)`、`fetch(RecordMapper)`、`intoArray(Field, Class)`、`intoSet(Field, Class)`、`fetch(Field, Class)`、`newRecord(Table)`を誤検出しないことを確かめる。
MapStruct、ModelMapper、Dozerはテストのクラスパスにないため、`mappingLibrariesAreNotUsed`のフィクスチャは`DefaultRecordMapper`と`DefaultRecordUnmapper`への依存だけで確かめる。

規則を追加するときは、違反フィクスチャのクラスを一つ追加し、`eachRuleDetectsItsViolatingFixture`の行と`rulesFor()`に規則を加える。
この手順で、新しい規則が違反を検出し、規約どおりのコードを誤検出しないことを確かめる。

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
Class <archfixture.violating.order.application.ShipOrderCommandHandler> is meta-annotated with @Transactional in (ShipOrderCommandHandler.java:0)
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
- `noClassesShouldUseJavaUtilLogging`：`java.util.logging`を使わない。
- `featureCodeUsesOnlySlf4jFacade`：ベースパッケージ直下以外のクラスは、Logback、Log4j、Apache Commons LoggingのAPIに依存しない。
- `noClassesShouldUseJodaTime`：Joda-Timeを使わない。
- `noClassesShouldUseFieldInjection`：フィールドに`@Autowired`などの注入アノテーションを付けない。
- `autowiredMethodsAreNotUsed`：メソッドに`@Autowired`を付けない。
- `assertionsShouldHaveDetailMessage`：`assert`文と`new AssertionError()`に失敗の詳細を書く。
- `deprecatedApiShouldNotBeUsed`：`@Deprecated`のAPIを使わない。
- `oldDateAndTimeClassesShouldNotBeUsed`：`java.util.Date`などの旧日時APIを使わない。

`ProxyRulesArchTest`は、プロキシで動くアノテーションを付けたメソッドへの同一クラス内の直接呼び出しを検査する。
対象のアノテーションの一覧と規則の理由は、[バックエンドのJava実装規約](java-coding.md)の「プロキシで動くアノテーション」に示す。

## 日時

`DateTimeConventionsArchTest`は、レガシー日時型、`Clock`を受け取らない`now(...)`、`Instant`以外の`now(Clock)`、`System.currentTimeMillis()`、`ZoneId.systemDefault()`、許可点以外でのシステム`Clock`生成と`InstantSource.system()`を検査する。
システム`Clock`の生成は`DemoApplication.clock()`だけを許可する。
呼び出しに加えて、`Instant::now`のようなメソッド参照も検査する。
日時規約の決定は[ADR-006](../adr/ADR-006-utc-instant-absolute-time-policy.md)と[ADR-046](../adr/ADR-046-derive-local-dates-with-configured-business-zone.md)を参照する。

Error ProneはすべてのJavaコンパイルで`JavaTimeDefaultTimeZone`と`JavaUtilDate`をerrorとして検査する。

## 共通カラム

`JooqCommonColumnsArchTest`は、jOOQの生成クラスの`CREATED_*`、`UPDATED_*`、`PATCHED_*`フィールドを参照するクラスが、`com.example.demo.shared.infrastructure.persistence`の外にないことを検査する。
楽観的ロックで各モジュールが参照する`LOCK_NO`は対象外にする。
生成したRecordのgetterは検査しない。
共通カラムの扱いは[PostgreSQLの共通カラム](../database/postgresql-common-columns.md)、sharedモジュールの決定は[ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)を参照する。

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
カスタム規則の`message`は、ArchUnitのbecauseと同じ「理由。直し方：…。規約：…」の形にする。
`ArchitectureRuleMessageTest`が、この形と「規約：」のパスの実在を確かめる。

## 失敗したときの読み方と直し方

Spring Modulith、Error Prone、NullAway、SpotBugs、Spotlessの失敗の文は変えられないため、次の表で読む。

| ツール          | 典型的な失敗の文                                                                                     | 意味                                                                                          | 直し方                                                              | 文書                                                                             |
| --------------- | ---------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| Spring Modulith | `Module 'order' depends on non-exposed type ... within module 'inventory'!`                          | 他モジュールの内部パッケージの型を使っている                                                  | 相手のルートの`<Feature>Queries`かイベントで連携する                | [バックエンドアーキテクチャ](architecture.md)                                    |
| Error Prone     | `error: [JavaTimeDefaultTimeZone] LocalDate.now() is not allowed ...`                                | 角括弧の中がチェック名で、`(see https://errorprone.info/bugpattern/<チェック名>)`に説明がある | 日時のチェックは、注入した`Clock`と設定値の`ZoneId`で求める形に直す | [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)                  |
| NullAway        | `error: [NullAway] dereferenced expression 's' is @Nullable`                                         | `@NullMarked`のコードで、`@Nullable`の値をnullを確かめずに使った                              | nullを確かめてから使うか、値を必ず渡して`@Nullable`を外す           | [NullAway, Error Messages](https://github.com/uber/NullAway/wiki/Error-Messages) |
| SpotBugs        | `Verification failed: SpotBugs ended with exit code 1. See the report at: ...`                       | バイトコードからバグのパターンを検出した                                                      | レポートのバグのパターンの説明と行を見て直す                        | [Lintとテストのリファレンス](../tooling/lint-and-test.md)                        |
| Spotless        | `The following files had format violations:`、`Run './gradlew spotlessApply' to fix all violations.` | Google Java Formatの形と違う                                                                  | `task be-format`を実行する                                          | [Lintとテストのリファレンス](../tooling/lint-and-test.md)                        |

## 解析対象と実行

プロダクションコード向けArchUnit検査は[ProductionCodeOnly](../../backend/src/test/java/com/example/demo/architecture/ProductionCodeOnly.java)で手書きコードだけを選び、生成コードとテストコードを除外する。
生成コードを除外しても、手書きコードからjOOQ APIや生成型への依存は検査する。

静的解析は`task be-lint`で、ArchUnitとSpring Modulithの検査は`task test`で実行する。
機能追加後の実行順は[バックエンドの機能追加時の確認](runbook-add-feature.md)を参照する。
