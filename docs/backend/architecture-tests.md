---
type: Reference
title: バックエンドのアーキテクチャテスト
description: Spring Modulith、ArchUnit、PMD、Error Proneでバックエンド規約を検査する実装と解析対象を示し、規約違反の検出方法または解析対象を確認するときに読むリファレンス。
tags: [reference, backend, testing, archunit, spring-modulith]
---

# バックエンドのアーキテクチャテスト

Spring ModulithとArchUnitはモジュール境界、依存方向、配置、禁止API、テスト規約を検査する。
PMDとError Proneはソースまたはコンパイル時に判定する規約を検査する。

## 検査クラス

```text
backend/src/test/java/com/example/demo/architecture/
├── ApplicationModuleArchitectureTest.java
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

`PackageByFeatureOnionArchitectureTest`はArchUnitの`Architectures.onionArchitecture()`と追加規則で、次の内容を検査する。

- Domain Modelは`com.example.demo.*.domain.model..`に置く。
- Domain Serviceは`com.example.demo.*.domain.service..`に置く。
- Application Serviceは`com.example.demo.*`または`com.example.demo.*.application..`に置く。
- Presentation Adapterは`com.example.demo.*.presentation..`に置く。
- Persistence Adapterは`com.example.demo.*.infrastructure.persistence..`に置く。
- Messaging Adapterは`com.example.demo.*.infrastructure.messaging..`に置く。
- 外部Client Adapterは`com.example.demo.*.infrastructure.client..`に置く。
- jOOQ APIと生成型は`infrastructure.persistence`だけで使う。
- 機能ルートの公開契約は内部パッケージの型へ依存しない。
- DomainはSpring、jOOQ、JPA、Jacksonに依存しない。
- `@Controller`と`@RestController`を付けた型は`presentation`に置く。
- `@Service`を付けた型は`application`に置く。
- `@Repository`を付けた型は`infrastructure.persistence`に置く。
- クラス単位の`@Transactional`を禁止する。
- `@Transactional`メソッドは`application`のpublicメソッドに限定する。

ベースパッケージ直下の起動クラスと全体設定は、オニオン規則の所属検査から除外する。
パッケージ構造の決定は[ADR-002](../adr/ADR-002-package-by-feature-onion-architecture.md)を参照する。

## 共通実装とプロキシ

`GeneralCodingRulesArchTest`は、標準ストリーム、`java.util.logging`、ロギング実装API、`@Autowired`によるメソッドインジェクションなどの禁止規則を検査する。

`ProxyRulesArchTest`は、次のアノテーションを付けたメソッドへの同一クラス内の直接呼び出しを検査する。

- Springの`@Transactional`、`@Async`、`@Cacheable`、`@CachePut`、`@CacheEvict`。
- Spring Securityの`@PreAuthorize`、`@PostAuthorize`、`@PreFilter`、`@PostFilter`、`@Secured`。
- Resilience4jの`@CircuitBreaker`、`@Retry`、`@RateLimiter`、`@Bulkhead`、`@TimeLimiter`。

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

## 解析対象と実行

プロダクションコード向けArchUnit検査は[ProductionCodeOnly](../../backend/src/test/java/com/example/demo/architecture/ProductionCodeOnly.java)で手書きコードだけを選び、生成コードとテストコードを除外する。
生成コードを除外しても、手書きコードからjOOQ APIや生成型への依存は検査する。

静的解析は`task be-lint`で、ArchUnitとSpring Modulithの検査は`task test`で実行する。
機能追加後の実行順は[バックエンドの機能追加時の確認](runbook-add-feature.md)を参照する。
