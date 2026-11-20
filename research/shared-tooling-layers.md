# 共有の道具の層と入口（#318 の調査）

この文書は #318 の調査の記録で、merge しない。
結論は #318 のコメントと同じで、作業は #569 で行う。
向こうは `yokozi-jp/agent-software-factory`（main、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `63e5c9f`）である。

## 結論

一部採用とする。
向こうの ADR 0051 と ADR 0054 のうち、次の 2 つを取り込む。

- **入口が出す型を決める**（ADR 0051）：`shared` の名前付きインタフェース `persistence` を、パッケージの単位から型の単位に絞る。
  他のモジュールの本番のコードが使ってよい型は `TableWriter`、`CommonColumns`、`LockedRoot`、`NullToEmptyStringConverter` の 4 つで、ほかの型への依存は `ApplicationModules.verify()` が止める。
  Spring Modulith 2.1.2 の `@NamedInterface` は型にも付けられるので、自作の規則は足さない。
- **規則を名前の一覧ではなく層の名前で届かせる**（ADR 0054）：`shared` の「どの層からも使えるパッケージ」を、ArchUnit の規則と docs に一覧で書かない。
  `shared.infrastructure..` は他のモジュールの `infrastructure.persistence` だけが使い、`shared` のそれ以外のパッケージはどの層からも使える、という構造で規則を書く。

ドライバの型（外部の道具を包む package を `domain` と `infrastructure` の 2 層に揃える形）、ドライバが `Promise` と `AbortSignal` を使う規則、入口ごとの外部ライブラリの表、package の役割の tag、`drivers.json` は取り込まない。
作業の Issue を 1 つ起こした（#569）。
ADR-048 は Proposed なので、#569 で Decision を直接書き直す（`docs/adr/conventions.md:66`）。

## 向こうの決定

- **ADR 0051**：共有 package の `exports`（入口）を、Effect や Kysely のような手段ではなく機能ごとに切る（0051:16）。
  `.` は外部ライブラリを import しない契約だけにし（0051:17）、入口ごとに触ってよい外部ライブラリを表と lint の設定に書く（0051:19）。
  目的は差し替えではなく、コードを読む負荷を下げることである（0051:12）。
  package を純粋なものと DB や HTTP に触るものに分ける案は、package ごとの手間が増えるとして退けた（0051:63）。
- **ADR 0054**：外部のシステムや道具を直接触り、業務の規則を持たない共有の package（db、hr-system、i18n、logging、telemetry）を「ドライバ」と呼ぶ（0054:22）。
  ドライバの層はモジュールと同じ名前の `domain` と `infrastructure` だけにし、名前が同じなので、層の glob で書いた今の規則が新しい規則なしでドライバに届く（0054:23）。
  モジュールでは `infrastructure` だけがドライバを import し（0054:26）、ドライバは `Promise` を返して Effect を import せず（0054:27）、非同期の関数は `AbortSignal` を受け取る（0054:28）。
  外部システムの Adapter は package に分けず、モジュールの `infrastructure` に置く（0054:46）。
  道具が決める置き場（dbmate の `migrations/` など）は `drivers.json` に書き、lint と repo-policy が同じファイルを読む（0054:41）。
- **package の役割の tag**：各 `package.json` の `nx.tags` に `type:module`、`type:driver` などをちょうど 1 つ書かせ（`docs/domain-model/package-layout.md:13`）、`tools/scripts/src/checks/repo-policy/roles.ts:46` の `packagesHaveOneRole` が、tag がない、2 つある、役割を名乗らない package を止める。
  役割ごとの検査（`modules.ts`、`drivers.ts`）は、名前の一覧ではなく tag で対象を選ぶ（`roles.ts:1-5`）。

## こちらの今の形

- `shared` は、機能モジュールではない唯一の技術的な共有モジュールで、`@Modulithic(sharedModules = "shared")`（`DemoApplication.java:13`）にしている（ADR-048、`docs/backend/architecture.md:150-180`）。
- 公開するパッケージは 5 つの名前付きインタフェースである。
  `concurrency`、`failure`、`events`、`security` はどの層からも使え、`persistence`（`shared.infrastructure.persistence`）は他のモジュールの `infrastructure.persistence` だけが使える（`architecture.md:172-173`）。
- `persistence` はパッケージの `package-info.java:11` に `@NamedInterface("persistence")` を付けており、パッケージの public な型 11 個がすべて公開になる。
  他のモジュールの本番のコードが使うのは、そのうち `TableWriter`、`CommonColumns`、`LockedRoot` の 3 つと、jOOQ の生成物（`jooq` モジュール）が使う `NullToEmptyStringConverter` だけである。
  残りの `ColumnValues`、`DeletedRoot`、`EventPublicationResubmitOnce`、`JooqObservationConfig`、`ModuleRoleTransactionManager`、`ModuleRoles`、`PgmCdAspect` は、`shared` の中か、テストのコードだけが使う（`git grep` で確かめた）。
- 外部システムの Adapter は、向こうと同じく、モジュールの `infrastructure.client` に置く（`payment.infrastructure.client.PaymentGatewayClient`）。
  外部の道具を直接触るのは Spring Boot の starter と自動構成（jOOQ、`RestClient`、Micrometer、OpenTelemetry の Logback の appender）で、組み立ての設定はベースパッケージ直下に置く（`architecture.md:219-229`）。

## 取り込むもの

### `persistence` の名前付きインタフェースを型の単位に絞る

向こうの ADR 0051 は、入口ごとに何を出すかを決め、出さないものを lint で止める。
こちらの `persistence` は、パッケージの public な型をすべて出している。
public にしてある理由は、`shared` の中の別のパッケージか、テストのコードが使うためで、他のモジュールの本番のコードに使わせるためではない。

今は、他のモジュールの `infrastructure.persistence` が `EventPublicationResubmitOnce` や `ModuleRoles` を使っても、どの検査も止めない。
`JooqOrderRepository` に `static final Class<?> PROBE = EventPublicationResubmitOnce.class;` を足し、`com.example.demo.architecture.*` の 18 のテストクラスを流すと、すべて通った（下の「確かめたこと」）。
`EventPublicationResubmitOnce.java:36` のコメントは「他のモジュールの本番のコードからは ArchUnit が使わせない」と書いているが、`sharedModuleIsUsedOnlyByPersistenceAdapters` は `infrastructure.persistence` からの依存を許すので、`infrastructure.persistence` からの使用は止まらない。
このコメントは事実と違う。

Spring Modulith の `@NamedInterface` は、パッケージにも型にも付けられる（2.1.2 の `NamedInterface.java:33` の `@Target({ ElementType.PACKAGE, ElementType.TYPE })`、Javadoc の「mark a package as named interface … or assign a type to a named interface」）。
`NamedInterfaces.discoverNamedInterfaces`（`NamedInterfaces.java:83-88`）は、注釈の付いたパッケージに加えて、注釈の付いた型からも名前付きインタフェースを組み立てる（`:294-315`）。
`propagate` は既定で `true` で、注釈の付いた型の public なメソッドの引数と戻り値の型のうち、同じパッケージにある型も同じ名前付きインタフェースに入る（`NamedInterface.java:58-66`）。
そのため、`TableWriter.deleteCheckingVersion` が返す `DeletedRoot` には注釈を付けなくてよい。

パッケージの注釈を外し、4 つの型に `@NamedInterface("persistence")` を付けると、次のとおりになった。

- 今のコードのまま、`com.example.demo.architecture.*` の 18 のテストクラスがすべて通る。
- 上の `PROBE` を足すと、`verifiesApplicationModuleStructure` が「Module 'ordering' depends on non-exposed type com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitOnce within module 'shared'!」で失敗する。
- `NullToEmptyStringConverter` の注釈を外すと、「Module 'jooq' depends on non-exposed type com.example.demo.shared.infrastructure.persistence.NullToEmptyStringConverter」で失敗する。
  jOOQ の生成物が forced type の Converter として使うためで、この型は公開に残す。

テストのコードは `ApplicationModules` の検査の対象ではないので、`ModuleRoles` や `ModuleRoleTransactionManager` を使うテスト（`DatabaseConventionsArchTest`、`AsModule`、`DatabaseTest` など）は変えなくてよい。
名前付きインタフェースの名前は `persistence` のままなので、#565 が書く `"shared :: *"` もそのまま通る。

型の単位にすると、公開する型を足すときに注釈を付ける手間が 1 つ増える。
付け忘れると `verify()` が止めるので、気付かずに漏れることはない。

### `shared` の語彙のパッケージを、一覧ではなく構造で規則に入れる

向こうの ADR 0054 は、ドライバの層の名前をモジュールと同じにして、今の規則を新しい規則なしで届かせた（0054:23）。
役割ごとの検査も、名前の一覧ではなく tag で対象を選ぶ（`roles.ts:1-5`）。

こちらは、「どの層からも使える `shared` のパッケージ」を名前の一覧で持つ。
同じ 4 つの名前（`shared.concurrency`、`shared.failure`、`shared.events`、`shared.security`）が、次の箇所に別々に書いてある。

- `PackageByFeatureOnionArchitectureTest.java:343-348`（`dependenciesPointInwardRule` の所属検査から除くパッケージ）
- `PackageByFeatureOnionArchitectureTest.java:394-420`（`sharedModuleIsUsedOnlyByPersistenceAdaptersRule` の除外と `because`）
- `docs/backend/architecture.md:173`、`:201`
- `docs/backend/architecture-tests.md:49`、`:51`、`:53`

ADR-079 が `shared.events` を、ADR-096 が `shared.security` を足したとき、どちらもこれらの全部を直した。
`architecture.md` のパッケージ構成の図（`:42-56`）には `events/` と `security/` がなく、`:229` の「例外は…」の列挙にも `security` がない。
一覧が 1 つ直らずに残っている。

また、#540 は、CommandHandler のトランザクションの中で呼ぶ冪等性の共通の処理を `shared` に置く（#540 の「やること」）。
`application` から呼ぶ入口と jOOQ を使う実装に分かれるので、`shared` に語彙のパッケージがもう 1 つ増える見込みが高い。

規則を次の構造にする。

- `shared.infrastructure..` は、他のモジュールの `infrastructure.persistence` だけが使える（今の `sharedModuleIsUsedOnlyByPersistenceAdapters` の除外を、4 つの名前から「`shared.infrastructure..` の外」に変える）。
- `shared` のそれ以外のパッケージは、どの層からも使える（`dependenciesPointInwardRule` は `shared..` を所属検査から除く。`shared.infrastructure.persistence` はオニオン規則の `persistence` の層（`basePackage + ".*.infrastructure.persistence.."`）に入るので、除いても検査は弱まらない）。
- docs は、語彙のパッケージの一覧を `architecture.md` の「共有モジュール shared」の 1 か所だけに置き、規則の説明は構造で書く。

`domain.model` と `domain.service` が使える `shared` のパッケージは、#566 のとおり `shared.concurrency` と `shared.failure` の許可リストのままにする。
`domain` に使わせるかどうかはパッケージごとの判断で、構造からは決まらないためである。

## 取り込まないもの

- **ドライバの型と、`domain` と `infrastructure` の 2 層**（ADR 0054:22-23）：向こうのドライバ（db、hr-system、i18n、logging、telemetry）の役は、こちらでは Spring Boot の starter と自動構成が持つ。
  jOOQ の `DSLContext`、`RestClient`、`MessageSource`、Micrometer、Logback は、どれもモジュールから直接使える形で Spring が組み立てる。
  これらを包む共有のモジュールを作ると、中身のない薄い包みになる。
  向こうの ADR 0051 も、db と i18n は中身のほとんどが外部依存の薄い包みで `domain` が空になるとしている（0051:64）。
  外部システムの Adapter をモジュールの `infrastructure` に置く点（0054:46）は、こちらの `infrastructure.client` と同じである。
- **ドライバは `Promise` を返し、`AbortSignal` を受け取る**（0054:27-28）：Effect の中と外の両方から呼ぶための規則で、こちらに Effect に当たるものはない。
  外部の呼び出しを止める上限は、`PaymentGatewayClient.java:76-79` が `RestClient` の接続と読み取りの timeout で持つ。
- **入口ごとの外部ライブラリの表**（0051:19）：こちらは、技術ごとの ArchUnit の規則で同じことを止めている。
  jOOQ は `databaseTechnologyApisAreOnlyUsedByPersistenceAdapters` が `infrastructure.persistence` に限り、HTTP の型は `sharedModuleDoesNotDependOnHttp` が `shared` から止める（#566 が各モジュールの `domain`、`application`、`infrastructure.persistence` に広げる）。
  Java の import はパッケージの単位で読めるので、表を別に持つと規則のコードと二重になる。
- **機能ごとに入口を切る**（0051:16）：`shared.infrastructure.persistence` には、jOOQ の共通処理と、イベント出版の再投入（ADR-079）が同居している。
  再投入を別のパッケージ（`shared.events.infrastructure.persistence` など）へ分けることも考えたが、取り込まない。
  再投入のクラスのうち他のモジュールが使うのは `EventPublicationResubmitOnce` だけで、それもテストだけである。
  型の単位の公開で、他のモジュールから見える型は 4 つに絞れる。
  分けると、上の構造の規則（`shared.infrastructure..` とそれ以外）に、`shared.events` の下の `infrastructure` という例外ができる。
  また、#562 が再投入を Spring Modulith 2.2 の `AbandonPolicy` に置き換えるので、今分けても #562 で書き直す。
- **package の役割の tag と、役割がちょうど 1 つの検査**（`roles.ts:46`）：#340 で見送った（`research/module-structure-and-entry.md` の「取り込まないもの」）。
  こちらは shared モジュールを `@Modulithic(sharedModules)` で、生成物を `@ApplicationModule(type = OPEN)` で区別し、#565 が全モジュールに `allowedDependencies` の宣言を必須にする。
- **`drivers.json`**（0054:41）：こちらは 1 つの Gradle のプロジェクトで、道具が決める置き場（Liquibase の changelog、`src/generated/jooq`）は Gradle と Spring Boot の設定にある。
  lint と repo-policy の 2 つが同じ表を読む問題が、こちらにはない。

## ほかの Issue との関係

- **#565（`allowedDependencies` で依存先を宣言する）**：`"shared :: *"` は、パッケージの名前付きインタフェースと型の名前付きインタフェースの両方を含む。
  名前は `persistence` のままなので、#565 の値は変わらない。
  #569 は #565 の前でも後でもよい。
- **#566（HTTP の型を使える層と domain の依存先を絞る）**：`domain` の許可リストに書く `shared.concurrency..` と `shared.failure..` は、構造ではなく一覧のままにする。
  #569 の構造の規則とは対象が違い、食い違わない。
- **#540（Idempotency-Key）**：`shared` に冪等性の処理を足すとき、#569 の後なら規則と docs の一覧を直さずに済む。
  #540 は #363 の後なので、#569 を先に入れられる。
- **#562（Spring Modulith 2.2 へ上げる）**：2.2.0 の spring-projects/spring-modulith#1778 は、一般に使える（shared の）モジュールの非公開の型を使ったときの違反のメッセージを分かりやすくする修正である。
  型の単位の公開は 2.1.2 で動くことを確かめたので、#562 を待たない。
  2.2.0 の spring-projects/spring-modulith#1839（名前付きインタフェースを型の単位でプログラムから登録する API）は、検出の方式を変えないこちらでは使わない（#340 と同じ判断）。
  #562 で再投入のクラスを書き直すときも、他のモジュールに見せる型を足さない。

## 確かめたこと

いずれも worktree（`research/shared-tooling-layers`、`origin/main` の `63e5c9f`）で、`./gradlew test --tests 'com.example.demo.architecture.*' --offline` を流した。
結果は `build/test-results/test/` の JUnit の XML で読んだ。
確かめたあと、変更はすべて戻した。

1. 今のコードの `JooqOrderRepository` に `static final Class<?> PROBE = EventPublicationResubmitOnce.class;` を足すと、18 のテストクラスがすべて通った（失敗 0）。
2. `shared/infrastructure/persistence/package-info.java` の `@NamedInterface("persistence")` を外し、`TableWriter`、`CommonColumns`、`LockedRoot`、`NullToEmptyStringConverter` の宣言に `@NamedInterface("persistence")` を付けた。
   `PROBE` なしで 18 のテストクラスがすべて通った（`ArchitectureRuleFixtureTest` 126 件、`ArchitectureRuleMessageTest` 189 件を含む）。
3. 2 に `PROBE` を足すと、`ApplicationModuleArchitectureTest.verifiesApplicationModuleStructure` が「Module 'ordering' depends on non-exposed type com.example.demo.shared.infrastructure.persistence.EventPublicationResubmitOnce within module 'shared'!」で失敗した。
4. 2 から `NullToEmptyStringConverter` の注釈だけを外すと、同じテストが「Module 'jooq' depends on non-exposed type com.example.demo.shared.infrastructure.persistence.NullToEmptyStringConverter」で失敗した（22 件）。
5. `shared.infrastructure.persistence` の public な型を他のモジュールの本番のコードで使う箇所を `git grep` で数えた。
   `TableWriter`、`CommonColumns`、`LockedRoot` は機能モジュールの `Jooq<Aggregate>Repository` が使い、`NullToEmptyStringConverter` は `backend/gradle/database.gradle` の jOOQ の設定から生成物が使う。
   ほかの 7 つの型は、`shared` の中かテストだけが使う。
6. Spring Modulith の最新版は 2.2.0 である（Maven Central の `maven-metadata.xml` の `release`）。
   2.2.0 のリリースノートに、型の単位の `@NamedInterface` の判定を変える項目はない。

## 出典

- 向こう（commit `29ba897`）：`docs/adr/0051-shared-package-entries-by-feature.md`、`docs/adr/0054-drivers-with-domain-and-infrastructure-layers.md`、`drivers.json`、`docs/domain-model/package-layout.md`、`tools/scripts/src/checks/repo-policy/roles.ts`、`tools/scripts/src/checks/repo-policy/packages.ts`、各 `packages/*/package.json` の `nx.tags`。
- こちら（commit `63e5c9f`）：`docs/adr/ADR-048-add-shared-module-for-jooq-common-code.md`、`docs/adr/ADR-067-open-jooq-generated-module.md`、`docs/backend/architecture.md`、`docs/backend/architecture-tests.md`、`backend/src/main/java/com/example/demo/shared/`、`backend/src/test/java/com/example/demo/architecture/PackageByFeatureOnionArchitectureTest.java`。
- Spring Modulith 2.1.2 のソース（`spring-modulith-api-2.1.2-sources.jar` の `org/springframework/modulith/NamedInterface.java`、`spring-modulith-core-2.1.2-sources.jar` の `org/springframework/modulith/core/NamedInterfaces.java`）。
- [Spring Modulith リファレンス：Fundamentals（Named Interfaces）](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- [Spring Modulith 2.2 GA のリリースノート](https://github.com/spring-projects/spring-modulith/releases/tag/2.2.0)（#1778、#1839、#1924）
- [Maven Central：spring-modulith-core の maven-metadata.xml](https://repo1.maven.org/maven2/org/springframework/modulith/spring-modulith-core/maven-metadata.xml)
