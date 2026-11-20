# モジュールの中の構成と、外に見せる入口（#340 の調査）

この文書は #340 の調査の記録で、merge しない。
結論は #340 のコメントと同じで、作業は #565 と #566 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `e29ab2a3`）である。

## 結論

一部採用とする。

route（こちらの Controller）をモジュールが持つことと、モジュールの公開の入口をモジュールのルートに限ることは、向こうとこちらで同じ決定になっている。
この 2 つは変えない。

向こうの ADR 0026 から、次の 3 つを取り込む。

- **モジュールの依存先の宣言**：向こうは、モジュールが依存してよいモジュールを各 package の `package.json` に書き、書いていないモジュールの import を型の検査で止める。
  こちらは、各モジュールの `package-info.java` に `@ApplicationModule(allowedDependencies = ...)` を書き、`ApplicationModules.verify()` に止めさせる。
  手書きのテスト `featureModulesDependOnlyInDeclaredDirections` と、その中のモジュールの一覧 `FEATURES` は消す。
- **HTTP の型を使える層の制限**：向こうは Hono の import を `http` の層と入口だけに限る。
  こちらは、Spring Web、Spring の HTTP、Servlet の型を、`application`、`domain`、`infrastructure.persistence` から使えなくする。
  今は `shared` にだけかけている ArchUnit の規則を広げる。
- **`domain` の依存の許可リスト**：向こうの `domain` は外部のライブラリと Node.js の組み込みを全部止める。
  こちらの `domain.model` は、Spring、jOOQ、JPA、Jackson を名指しで止めるだけなので、名指していないライブラリ（SLF4J、`java.net.http` など）は止まらない。
  `domain.model` と `domain.service` の依存先を、標準の一部の package と自モジュールの Domain と `shared` の語彙だけの許可リストにする。

モジュールごとの組み立ての入口（`index.ts` が `http` と `infrastructure` をつなぐ形）、`apps/api` への route の登録とその検査、application の port に保存とトランザクションを持たせる形、モジュールの中のファイルの循環の検査、package の役割の tag は、取り込まない。

Spring Modulith 2.2.0 の `:: **`（spring-projects/spring-modulith#1924）と、名前付きインタフェースを型に登録する API（spring-projects/spring-modulith#1839）は、この決定を変えない。
2.2 へ上げることは #562 のままでよく、この作業は 2.1.2 で進められる。

作業の Issue を 2 つ起こした（#565、#566）。

## 比べたもの

### 向こう

- ADR 0026（`docs/adr/0026-modules-as-packages-with-oxlint-boundaries.md`）。
  - モジュールは `packages/<module>` の package で、公開 API は `package.json` の `exports` の `"."`（`src/index.ts`）だけである（:21）。
  - モジュールの間の向きは「締め → 承認 → 申請」で、依存の宣言（`package.json`）に書いていないモジュールは、pnpm が `node_modules` に置かないため、TypeScript が解決できない（:22-24）。
    宣言の向きは repo-policy が `moduleOrder` と照らす。
  - 層は `domain`、`application`、`infrastructure`、`http` で、`domain` は何も import せず、`application` は `domain` だけを、`infrastructure` と `http` は `application` と `domain` を import し、互いには import しない（:26、`vite.config.ts:207`）。
  - Hono を import できるのは `http` の層、`apps/api/src/app.ts`、入口の `server.ts`、テストだけである（:27、yokozi-jp/agent-software-factory#553）。
    理由は「`domain`、`application`、`infrastructure` が Hono に依存すると、業務の規則と DB の実装が HTTP の枠組みに縛られる」である。
  - モジュールの route は `packages/<module>/src/http/route/` の sub-app で、モジュールの `index.ts` が `<module>Router({ runtime })` を export し、`app.ts` が `.route("/", claimRouter({ runtime }))` を 1 行ずつ連鎖させる（:29-35、yokozi-jp/agent-software-factory#554）。
    連鎖させるのは、Hono の `AppType` がメソッドをつないだ型からしか作られないためである。
    repo-policy の `modulesAreRegistered` が、route を持つモジュールの登録の漏れを止める（:20）。
  - `import/no-cycle` で、package の中のファイルの循環を止める（:37）。
  - Considered Options で、`http` 層を `apps/api` に移す形を「1 つの業務の変更が 2 つの package に分かれる」として退けた（:77）。
- 直近のコミット 5554592「申請の下書きを作る route を足し、モジュールの route の interface を決める」（yokozi-jp/agent-software-factory#749）。
  - `packages/claim/src/index.ts:16-18`：`claimRouter` は `runtime` だけを受け取り、`infrastructure` の `claimStore` を `http` の `buildClaimsRouter` に値で渡す。
  - `packages/claim/src/http/route/claims.ts:80-108`：route の handler が、文脈、冪等キー、現在時刻を 1 回読み、use case の `createClaim` に引数で渡す。
  - `packages/claim/src/application/claim-store.ts:24-43`：`application` は `infrastructure` のトランザクションの tag（`ClaimTx`）を名指せないので、保存とトランザクションを port（`ClaimStore`）の 1 つのメソッドにする。
  - `packages/claim/src/domain/claim.ts:35-38`：`domain` は `shared-kernel` の型（`Permit`）だけを import する。
  - `apps/api/src/app.ts:4`、`:102`：`claimRouter` の import と `.route()` の 1 行。

### こちら

- ADR-001：`com.example.demo` の直接のサブパッケージをモジュールにし、起動時の検証で依存の違反があれば起動を止め、モジュールの間を公開 API とイベントに限る（ADR-001:31-32）。
- ADR-002：package by feature とオニオンアーキテクチャで、モジュールのルートに公開の契約だけを置き、依存を内向きにする（ADR-002:32、:35）。
- ADR-050：クラスの役割、置き場所、命名と、モジュールの間の連携をイベントと `<Feature>Queries` に限ること。
- `docs/backend/architecture.md:148`：「機能モジュールは Spring Modulith の既定の閉じたモジュールとし、`@ApplicationModule` と `@NamedInterface` を付けない。」
- `docs/backend/architecture.md:195-203`、`docs/backend/layers.md:27`、`:62`、`:75`、`:84-85`：層の依存の向き。
- `ApplicationModuleArchitectureTest.java:21`、`:30-63`：機能モジュールの一覧 `FEATURES` と、直接の依存先を期待の表と比べるテスト。
- `PackageByFeatureOnionArchitectureTest.java`：`dependenciesPointInward`（:318-339）、`sharedModuleDoesNotDependOnHttp`（:408-420）、`domainModelDoesNotDependOnFrameworks`（:123-142）、`domainServicesDependOnlyOnDomainAndJavaRule`（:440-461）。
- `DatabaseConventionsArchTest.java:92`、`:166-178`：`persistenceAdaptersUseOnlyOwnGeneratedSchema`。
- ADR-067：jOOQ の生成物 `com.example.demo.jooq` を OPEN のモジュールにする。

## 同じ決定になっているもの

- **route をモジュールが持つ**：こちらの Controller は、機能モジュールの `presentation.web` に置く（`controllersResideInPresentationWeb`、`PackageByFeatureOnionArchitectureTest.java:186`）。
  向こうが yokozi-jp/agent-software-factory#553 で `http` 層を `apps/api` に移す形を退けた理由（一緒に変わるものを分けない）は、ADR-002 の package by feature の理由と同じである。
- **公開の入口をモジュールのルートに限る**：向こうの `exports` の `"."` は、こちらの Spring Modulith のモジュールのルート（名前のないインタフェース）に当たる。
  こちらの機能モジュールは名前付きインタフェースを持たず、`application`、`domain`、`presentation`、`infrastructure` は、他のモジュールから使うと `verify()` が止める。
  ルートの型は標準の型と同じルートの型だけに限る（`moduleApiDoesNotExposeInternalTypes`）。
- **層の外に置いたクラスを止める**：向こうは「`src/` に置けるのは `index.ts`、層のディレクトリ、`__fixtures__/` だけ」を repo-policy で止める。
  こちらは `ensureAllClassesAreContainedInArchitectureIgnoring`（`PackageByFeatureOnionArchitectureTest.java:326-331`）が止める。
- **規則の検出力**：向こうは規則ごとに fixture を置き、規則が外れると使われない disable のコメントで失敗させる。
  こちらは `ArchitectureRuleFixtureTest` が、違反のフィクスチャを規則が検出することを確かめる。

## 取り込むもの

### モジュールの依存先を allowedDependencies で宣言する

向こうは、モジュールが依存してよいモジュールをモジュール自身の `package.json` に書く。
宣言にない import は解決できないので、新しいモジュールも、足した時点で宣言の外の依存を持てない。

こちらには、同じ役割の宣言がモジュールの側にない。
`docs/backend/architecture.md:148` が `@ApplicationModule` を付けないと決めているため、Spring Modulith はどの機能モジュールも「依存先を宣言していない」ものとして扱う。
宣言していないモジュールは、他のモジュールの公開の型のどれにでも依存できる（Spring Modulith のリファレンスの Explicit Application Module Dependencies）。
`verify()` が止めるのは、循環と内部の型への依存だけである。

そのため、こちらは向きを手書きのテストで確かめている。
`featureModulesDependOnlyInDeclaredDirections`（`ApplicationModuleArchitectureTest.java:30-63`）は、`FEATURES` の 4 つのモジュールの直接の依存先を、期待の表と比べる。
このテストには 2 つの穴がある。

- `FEATURES` は手で持つ一覧で、Spring Modulith が見つけたモジュールと照らしていない。
  新しい機能モジュールを足して `FEATURES` に足し忘れると、そのモジュールの依存先も、そのモジュールへの依存も、比べる対象から外れる（`.filter(FEATURES::contains)`、:48）。
  向こうの `moduleOrder` は、repo-policy が `type:module` の package と照らすので、この漏れが起きない。
- 期待の表がテストの中にあり、モジュールを読む人がモジュールの側で依存先を知れない。

Spring Modulith の仕組みで置き換える。
各モジュールの `package-info.java` に `@ApplicationModule(allowedDependencies = ...)` を書くと、`verify()` が宣言にない依存を止める。
`verify()` は ADR-001 の起動時の検証でも動くので、宣言にない依存を持つアプリは起動しない。
今の依存から決めた宣言は次のとおりである（下の「確かめたこと」で `verify()` が通ることを確かめた）。

| モジュール | `allowedDependencies`                       |
| ---------- | ------------------------------------------- |
| `product`  | `"jooq"`                                    |
| `ordering` | `{"product", "shared :: *", "jooq"}`        |
| `payment`  | `{"ordering", "shared :: *", "jooq"}`       |
| `tenancy`  | `{"shared :: *", "jooq"}`                   |
| `error`    | `"shared :: *"`                             |
| `shared`   | `"jooq"`                                    |

宣言の書き方で、次の 3 点を確かめた。

- `@Modulithic(sharedModules = "shared")` の shared モジュールは、宣言しなくても許可先に入る。
  ただし、入るのは `shared` のルート（名前のないインタフェース）だけで、`shared :: concurrency` などの名前付きインタフェースは入らない（Spring Modulith 2.1.2 の `ApplicationModule.java:975-980` の `AllowedDependency.to`、2.2.0 の `:1010-1015` も同じ）。
  `shared` のルートには `package-info.java` しかないので、`shared` を使うモジュールは `"shared :: *"` を書く。
- `jooq` は OPEN のモジュールだが、宣言のあるモジュールからは、`"jooq"` を書かないと依存できない。
  OPEN のモジュールは、サブパッケージの型もルートのインタフェースに入るので、`"jooq"` だけで生成型の全部に届く。
- `"shared :: *"` は名前付きインタフェースの全部で、`shared` のルートを含まない。
  `shared` のルートに型はないので、2.2.0 の `"shared :: **"`（ルートを含む全部）と同じになる。

Spring Modulith には、宣言のないモジュールを違反にする設定がない（`VerificationOptions` は ArchUnit の規則を足すことしかできない）。
宣言のないモジュールは何にでも依存できるので、新しいモジュールで宣言を書き忘れると、向こうの `moduleOrder` の漏れと同じ穴になる。
そのため、OPEN の `jooq` 以外のすべてのモジュールが `allowedDependencies` を書いていることを確かめる小さなテストを 1 つ残す。
このテストは `ApplicationModules` が見つけたモジュールを全部回すので、一覧を手で持たない。
`JavaPackage.findAnnotation(ApplicationModule.class)`（2.1.2 で public）で宣言を読み、`ApplicationModule.OPEN_TOKEN`（既定値）でないことを確かめる。

宣言はモジュールの単位なので、モジュールの中のどの層が `shared` のどのパッケージを使えるかは表せない。
`sharedModuleIsUsedOnlyByPersistenceAdapters`（`shared.infrastructure.persistence` を他のモジュールの `infrastructure.persistence` だけに許す規則）は、ArchUnit のまま残す。

この決定は全モジュールにかかり、`docs/backend/architecture.md:148` の方針を変えるので、作業の Issue で新しい ADR を起こす。
既存の ADR と食い違うものはない（`@ApplicationModule` を付けない決定は docs にだけあり、ADR にはない）。

### HTTP の型を presentation.web と infrastructure.client に限る

向こうは Hono の import を `http` の層と入口に限り、`domain`、`application`、`infrastructure` を HTTP の枠組みから切り離す（ADR 0026:27）。

こちらで同じ役割の規則は、`shared` にかける `sharedModuleDoesNotDependOnHttp`（`PackageByFeatureOnionArchitectureTest.java:408-420`）だけである。
`application` と `infrastructure.persistence` から Spring Web、Spring の HTTP、Servlet の型を使っても、どの規則も止めない。
たとえば CommandHandler が `ResponseStatusException` を投げると、ADR-062 の業務上の失敗の例外と、`error` の `ApiExceptionHandler` での対応づけを通らない。
`docs/backend/class-roles/business-exception.md:55` は業務上の失敗の例外がこれらの型に依存しないとしているが、例外を投げる側の CommandHandler を縛る規則はない。

今のコードで、これらの型を使うのは `presentation.web`、`infrastructure.client`（`PaymentGatewayClient` の `RestClient`）、ベースパッケージ直下の設定、`error` の `presentation.web` だけで、規則を足しても違反はない。
Spring Modulith はモジュールの間の依存を見る仕組みで、モジュールの中の層が外部のライブラリを使うことは見ない。
代わる仕組みがないので、ArchUnit の規則にする。
新しい規則を足さず、`sharedModuleDoesNotDependOnHttp` の対象を `shared`、各モジュールの `domain`、`application`、`infrastructure.persistence` に広げる。

### domain の依存を許可リストにする

向こうの `domain` は、`shared-kernel` の型の `import type` だけを許し、ライブラリと Node.js の組み込みを全部止める（ADR 0026:26、:39）。

こちらの `domain.model` の規則（`domainModelDoesNotDependOnFrameworks`、`PackageByFeatureOnionArchitectureTest.java:123-142`）は、`org.springframework`、`org.jooq`、生成型、`jakarta.persistence`、Jackson を名指しで止める禁止の一覧である。
一覧にないライブラリは止まらない。
機能のコードは SLF4J でログを書く前提で（`featureCodeUsesOnlySlf4jFacade` はロギングの実装の API だけを止める）、`domain.model` でもログを書ける。
`java.net.http` の `HttpClient` も、どの規則も止めない。
`domain.service` は許可リスト（`domainServicesDependOnlyOnDomainAndJavaRule`、:440-461）だが、`java..` を全部許すので、`java.net.http` と `java.sql` は止まらない（`java.sql` だけは `databaseTechnologyApisAreOnlyUsedByPersistenceAdapters` が止める）。

今の `domain.model` が使うのは、`java.util`、`java.math`、`java.time`、`org.jspecify`、自モジュールの `domain.model`、`shared.concurrency`、`shared.failure` だけである。
`domain.model` の規則を、`java.lang..`、`java.util..`、`java.math..`、`java.time..`、`org.jspecify..`、`lombok..`、自モジュールの `domain`、`shared.concurrency..`、`shared.failure..` の許可リストに変える。
`domain.service` の `java..` も同じ 4 つに絞る。
ライブラリの使い方の規則なので、上と同じく Spring Modulith に代わる仕組みはない。

`Date` や `Math.random`、`throw` を止める向こうの規則（ADR 0027）は、ドメインモデルの書き方の問題で、この Issue の範囲の外である。
こちらの時刻は `DateTimeConventionsArchTest` と Error Prone が止めている。

## 取り込まないもの

- **モジュールごとの組み立ての入口（`index.ts` の `claimRouter` が `http` と `infrastructure` をつなぐ形）**：向こうは DI のコンテナを持たないので、モジュールの `index.ts` が実装を値で渡す。
  こちらは Spring の DI が `@Service`、`@Repository`、`@RestController` をつなぐので、組み立てのコードを書く場所がない。
- **`apps/api` への route の登録と、登録の漏れの検査（`modulesAreRegistered`）**：向こうが `app.ts` に 1 行ずつ書くのは、Hono の `AppType` が連鎖した型からしか作れないためである（ADR 0026:35）。
  こちらの Controller は、コンポーネントスキャンが見つけて Spring MVC に登録するので、書き忘れる一覧がない。
  Controller を `presentation.web` の外に置く誤りは `controllersResideInPresentationWeb` が止める。
- **application の port に保存とトランザクションを持たせる形（`ClaimStore`）**：向こうは、`application` が `infrastructure` のトランザクションの tag を名指せないため、port のメソッドがトランザクションを開く（`claim-store.ts:19-23`）。
  こちらは、CommandHandler の `handle` の `@Transactional` がトランザクションを開き、Repository を `domain.model` に置く（ADR-050 の選択肢1 で、ユースケースごとの出力の port を退けた）。
  向こうがこの形を選んだ理由は、こちらでは起きない。
- **リクエストごとの値の読み方（テナント、操作する人、冪等キー、現在時刻を route が 1 回読む形）**：それぞれ別のチケットで決めている。
  テナントと役割は #316 と #363、冪等キーは #310 と #540、現在時刻は `docs/datetime/` の「`Clock` をコンストラクタで受け取る」規則である。
- **package の中のファイルの循環を止める規則（`import/no-cycle`）**：こちらは、モジュールの中のパッケージの間の循環を、層の規則がすでに止める。
  オニオンの規則は外から内への向きだけを許し、`infrastructure` は `domain.model` だけに依存し（`infrastructureDependsOnlyOnDomainModel`）、ルートは標準の型と同じルートの型だけに依存する。
  残るのは同じパッケージの中のクラスの相互の参照で、Java は同じパッケージの相互の参照をそのままコンパイルし、型の参照で初期化の順序の問題を起こさない（判断）。
  向こうの ADR は止める理由を書いていない。
- **package の役割の tag（`type:module` など）**：こちらは、shared モジュールを `@Modulithic(sharedModules)` で、生成物を `@ApplicationModule(type = OPEN)` で、Spring Modulith の仕組みで区別している。
  上の採用で `FEATURES` の一覧がなくなるので、役割の一覧として残るのは DB のロールの `ModuleRoles.MODULES`（`ModuleRoles.java:19`）だけになる。
  これは #307 と #363 の範囲で、`ModuleRolesTest` が jOOQ のスキーマと Spring Modulith のモジュールと照らしている。
- **jOOQ の生成物のスキーマごとの名前付きインタフェース**（ADR-067 の選択肢1）：スキーマのパッケージ（`jooq.<schema>`、`tables`、`tables.records`）に `@NamedInterface("<schema>")` を付け、`ordering` が `"jooq :: ordering"` を宣言すると、`verify()` が別のスキーマの生成型への依存を止めることを確かめた。
  ADR-067 がこの形を退けた理由の一つ（「機能モジュールの側にも `allowedDependencies` を書かないと手間に見合わない」）は、上の採用で消える。
  それでも取り込まない。
  `persistenceAdaptersUseOnlyOwnGeneratedSchema` が守るのは「モジュールの名前とスキーマの名前が同じ」ことで（ADR-094 のモジュールのロール）、`allowedDependencies` はどの名前付きインタフェースを書いても通すため、この規則は残る。
  残る規則と同じ保証のために、生成のたびに 15 の `package-info.java` を書く手順を足すことは、費用に見合わない。
  ADR-067 は変えない。
- **Spring Modulith 2.2.0 の `NamedInterface` を型に登録する API**（spring-projects/spring-modulith#1839）：`ApplicationModuleDetectionStrategy` で名前付きインタフェースを組み立てるときに型の単位で選べるようにする変更である。
  こちらは検出の方式を変えておらず、型に付ける `@NamedInterface` の注釈は 2.1.2 にもある。
  使う場面がない。

## コメントへの回答

この Issue にコメントはない。

## ほかの Issue との関係

- **#562（Spring Modulith 2.2 へ上げる）**：この作業は 2.1.2 で進められ、#562 の前でも後でもよい。
  2.2.0 の `"order :: **"`（spring-projects/spring-modulith#1924）は、ルートと名前付きインタフェースの全部を 1 つで書く記法である。
  こちらで両方を持つモジュールは `shared` だけで、`shared` のルートには型がないので、`"shared :: *"` で足りる。
  2.2.0 でも、shared モジュールが暗黙に許可先に入るのはルートだけなので（`ApplicationModule.java:1010-1015`）、`"shared :: *"` を書くことは変わらない。
  #562 の後に `"shared :: **"` へ書き換える必要もない。
  2.2.0 の spring-projects/spring-modulith#1927（shared モジュールが自分を許可先に挙げる表示）は表示だけの修正で、判定は変わらない。
- **#307、#363（モジュールの DB のロール）**：`SchemaInspectionTest` の G5 は、モジュールのロールの SELECT の権限を、Spring Modulith が見つけた直接の依存先と照らす（`docs/backend/architecture-tests.md:348`）。
  `verify()` が「直接の依存先は宣言の中にある」ことを保証するので、G5 は今の形のままでよい。
  `ModuleRoles.MODULES` は残る。
- **#543（外部システムを呼ぶ CommandHandler を Listener からだけ呼ばせる ArchUnit の規則）**：モジュールの中のクラスの間の規則で、Spring Modulith に代わる仕組みがない点は同じである。
  この決定と食い違わない。
- **#309、#541、#317（Problem Details と変換する箇所）**：HTTP の型を `presentation.web` に限る規則は、HTTP の応答への変換を `error` と Controller に集めることを、`application` の側から支える。
- **#312、#560、#561（イベントのスキーマの置き場所）**：イベントはモジュールのルートの record のままで、送る側の events の package を別に置かないと決めた。
  `allowedDependencies` の宣言では、イベントを受けるモジュールが送るモジュールのルートへの依存を書く（`payment` の `"ordering"`）。
- **#316、#363、#310、#540**：上の「取り込まないもの」のとおり、リクエストごとの値はそれぞれのチケットで決めている。

## 限界

- `allowedDependencies` はモジュールの単位の宣言で、モジュールの中の層が何を使えるかは表せない。
  `sharedModuleIsUsedOnlyByPersistenceAdapters`、`persistenceAdaptersUseOnlyOwnGeneratedSchema`、オニオンの規則は ArchUnit のまま残る。
- `allowedDependencies` は「依存してよい」宣言で、宣言した依存が実際に使われていることは確かめない。
  使わなくなった依存先が宣言に残っても、`verify()` は通る。
  手書きのテストの期待の表は、余分な宣言も落としていた。
  余分な宣言を止めるなら、`getDirectDependencies` と宣言を比べる検査が要るが、宣言を広めに書いても境界が緩むのは宣言したモジュールの間だけなので、足さない（判断）。
- 確かめたのは 2.1.2 だけである。
  2.2.0 は、`getAllowedDependencies` と `AllowedDependency.to` のソースを読んで、同じ判定であることを確かめた。
- HTTP の型と `domain` の規則は、ArchUnit のクラスの依存で判定する。
  リフレクションや文字列でクラスを読む書き方は止まらない。
  Controller が `ResponseStatusException` を投げることは、Controller が `presentation.web` にあるので、この規則では止まらない（`docs/backend/class-roles/controller.md:259` の［自分で点検］のまま）。

## 確かめたこと

こちらのリポジトリ（main、`e29ab2a3`）の使い捨ての worktree で、`./gradlew compileJava` の結果のクラスに、`package-info.java` だけを差し替えたクラスを重ね、`ApplicationModules.of(DemoApplication.class).detectViolations()` を直接呼んで確かめた。
`./gradlew test` は、テストのコンテナの起動を含み、マシンの負荷で 15 分の上限を超えたため使っていない。

- 宣言のない今の形で、`payment` の `application` に `product.ProductQueries` を使うクラスを足しても、`verify()` は違反を出さない。
  `ordering` の `infrastructure.persistence` に `jooq.payment.Tables` を使うクラスを足しても、違反を出さない（こちらは `persistenceAdaptersUseOnlyOwnGeneratedSchema` が止める）。
- 上の表の宣言で、今のコードに違反はない。
- 上の表の宣言で、`payment` から `product` への依存は `Module 'payment' depends on module 'product' via com.example.demo.payment.application.LeakModule -> com.example.demo.product.ProductQueries. Allowed targets: ordering, shared :: *, jooq, shared.` で止まる。
- `shared :: *` を書かず `ordering` に `{"product"}` だけを宣言すると、`shared :: concurrency`、`shared :: failure`、`shared :: persistence` への依存がすべて違反になる。
  shared モジュールが暗黙に入るのはルートだけである。
- `"jooq"` を書かないと、OPEN の `jooq` への依存が `depends on module 'jooq'` の違反になる。
- `jooq.<schema>` に `@NamedInterface("<schema>")` を付けただけでは `tables` と `tables.records` の型が入らず、3 つのパッケージ全部に付けると `"jooq :: ordering"` の宣言で違反がなくなり、`ordering` から `jooq.payment` への依存が `depends on named interface(s) 'jooq :: payment'` で止まる。

Spring Modulith のソースで、次を確かめた。

- 2.1.2 の `ApplicationModule.getAllowedDependencies`（`ApplicationModule.java:511-531`）は、宣言がなければ `AllowedDependencies.open()` を返し、宣言があれば、宣言と shared モジュールのルートを許可先にする。
- 2.1.2 の `VerificationOptions` には、宣言のないモジュールを違反にする設定がない。2.2.0 も同じである。
- 2.1.2 の `JavaPackage.findAnnotation`（`JavaPackage.java:453`）は public で、パッケージの `package-info` の注釈を読める。

こちらのリポジトリで、次を確かめた。

- `org.springframework.web`、`org.springframework.http`、`jakarta.servlet` を import するのは、ベースパッケージ直下の設定 5 つ、`error.presentation.web`、各モジュールの `presentation.web`、`payment.infrastructure.client` だけである。
- `domain.model` の import は、`java.util`、`java.math`、`java.time`、`org.jspecify.annotations`、自モジュールの `domain.model`、`shared.concurrency`、`shared.failure` だけである。`domain.service` のクラスはない。
- 機能モジュールの依存先：`product` は `jooq`、`ordering` は `product`、`shared`、`jooq`、`payment` は `ordering`、`shared`、`jooq`、`tenancy` は `shared`、`jooq`、`error` は `shared`、`shared` は `jooq`（`jooq.modulith`）。

## 作業の Issue

- #565：モジュールの依存先を `allowedDependencies` で宣言し、手書きの依存の向きのテストを消す。
- #566：HTTP の型を使える層を絞り、domain の依存を許可リストにする。

## 出典

- [Spring Modulith Fundamentals（Explicit Application Module Dependencies、Named Interfaces、Open Application Modules）](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- [Spring Modulith 2.2.0 のリリースノート](https://github.com/spring-projects/spring-modulith/releases/tag/2.2.0)
- spring-projects/spring-modulith#1924、spring-projects/spring-modulith#1839、spring-projects/spring-modulith#1927
- [ApplicationModule.java（2.1.2）](https://github.com/spring-projects/spring-modulith/blob/2.1.2/spring-modulith-core/src/main/java/org/springframework/modulith/core/ApplicationModule.java)
- [ApplicationModule.java（2.2.0）](https://github.com/spring-projects/spring-modulith/blob/2.2.0/spring-modulith-core/src/main/java/org/springframework/modulith/core/ApplicationModule.java)
- 向こう：`docs/adr/0026-modules-as-packages-with-oxlint-boundaries.md`、`docs/domain-model/add-module.md`、`packages/claim/src/index.ts`、`packages/claim/src/http/route/claims.ts`、`packages/claim/src/application/create-claim.ts`、`packages/claim/src/application/claim-store.ts`、`packages/claim/src/application/authorize.ts`、`packages/claim/src/domain/claim.ts`、`packages/claim/src/infrastructure/claim-store.ts`、`apps/api/src/app.ts`、`vite.config.ts`、commit 5554592（yokozi-jp/agent-software-factory#749）、yokozi-jp/agent-software-factory#553、yokozi-jp/agent-software-factory#554
- こちら：ADR-001、ADR-002、ADR-050、ADR-062、ADR-067、ADR-094、`docs/backend/architecture.md`、`docs/backend/layers.md`、`docs/backend/architecture-tests.md`、`docs/backend/runbook-add-feature.md`、`docs/backend/class-roles/business-exception.md`、`docs/backend/class-roles/controller.md`、`ApplicationModuleArchitectureTest.java`、`PackageByFeatureOnionArchitectureTest.java`、`DatabaseConventionsArchTest.java`、`ModuleRoles.java`、`DemoApplication.java`
