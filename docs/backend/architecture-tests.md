---
type: Reference
title: バックエンドのアーキテクチャテスト
description: パッケージ構成、依存方向、配置規則、Java 実装規約を検証する ArchUnit、Spring Modulith、PMD の検査内容を示す。アーキテクチャテストや PMD が失敗したとき、検査規則を追加や変更するときに読む。
tags: [reference, backend, testing, archunit, spring-modulith]
---

# バックエンドのアーキテクチャテスト

モジュール間の依存は Spring Modulith の `ApplicationModules.verify()` で、機能モジュール内部の依存と配置は ArchUnit で検証する。
バイトコードに残らない Lombok の注釈と Logger の書き方は PMD で検証する。
実行コマンドは [機能追加時の確認](runbook-add-feature.md) に示す。

## テストクラス

パッケージ構成と依存方向は、次のテストで自動検証する。

``` text
backend/src/test/java/com/example/demo/architecture/
├── ApplicationModuleArchitectureTest.java
├── GeneralCodingRulesArchTest.java
└── PackageByFeatureOnionArchitectureTest.java
```

`ApplicationModuleArchitectureTest` は Spring Modulith の `ApplicationModules.verify()` を使い、モジュール間の循環、内部パッケージ参照、明示した許可依存への違反を検出する。

`GeneralCodingRulesArchTest` は標準ストリーム、`java.util.logging`、ロギング実装 API、`@Autowired` によるメソッドインジェクションなど、プロダクションコード全体の規則を検証する。

`PackageByFeatureOnionArchitectureTest` は ArchUnit の `Architectures.onionArchitecture()` を使い、機能モジュール内部の依存を検証する。

## オニオン規則

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

## 配置規則

同じテストは、オニオン規則だけでは表せない次の配置規則も検証する。

- jOOQ API と生成型は `infrastructure.persistence` だけで利用する。
- 機能ルートの公開契約は `domain`、`application`、`presentation`、`infrastructure` の内部型へ依存しない。
- Domain は Spring、jOOQ、JPA、Jackson に依存しない。
- `@Controller` と `@RestController` を付けた型は `presentation` に置く。
- `@Service` を付けた型は `application` に置く。
- `@Repository` を付けた型は `infrastructure.persistence` に置く。
- クラス単位の `@Transactional` は使用しない。
- `@Transactional` を付けるメソッドは `application` の public メソッドに限定する。

## PMD とその他の規則

Lombok の `@Data` と `@Setter` はコンパイル後のバイトコードに残らないため、ArchUnit ではなくソースを解析する PMD で禁止する。

同じ PMD 規則で、手書きの Logger フィールド、`LoggerFactory` の直接参照、`getLogger` の static import を禁止し、`@Slf4j` の使用へ統一する。

`@Autowired` を付けた通常メソッドは禁止し、依存注入にはコンストラクタを使う。

## 解析対象

ArchUnit は `ProductionCodeOnly` を通して手書きのプロダクションコードだけを解析し、テストコードと生成コードを解析対象から除外する。

生成コード自体を除外しても、手書きコードから jOOQ API や生成型への依存は検査する。
