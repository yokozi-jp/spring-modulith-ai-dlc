---
type: ADR
title: 'ADR-050: バックエンドのクラスの役割と命名を定める'
description: 機能モジュールのクラスを CQRS の役割名（CommandHandler、QueryService、Listener）と DDD の役割（集約、値オブジェクト、Repository、Domain Service）に分け、置き場所、命名、モジュール間の連携、ArchUnit による検査を定める決定。
tags: [adr, backend, architecture, ddd, cqrs]
---

# ADR-050: バックエンドのクラスの役割と命名を定める

## Status

Proposed

## Date

2026-10-03

## Context

[ADR-002](ADR-002-package-by-feature-onion-architecture.md) は、機能モジュールの内部を Domain、Application、Presentation、Infrastructure に分けると決めた。
しかし、各層に置くクラスの役割と命名は決めておらず、以前の `docs/backend/architecture.md` には次の問題があった。

- モジュールルートに置く `<Feature>Operations`、`<Command>`、`<Result>` が何を表すかの定義と例がなかった。
- Application の `<UseCase>Service` と Domain の Domain Service がどちらも `Service` を名乗り、名前から役割を区別できなかった。
- Repository のインタフェースを `domain` に置くのか `application.port` に置くのかが曖昧で、ヘキサゴナルアーキテクチャの port（[Cockburn, Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)）とオニオンアーキテクチャの Repository が混在していた。
- イベントの受信と送信を `infrastructure.messaging` に置くとしていた。`@ApplicationModuleListener` は新しいトランザクションを開くため、トランザクション境界が Infrastructure にできていた。
- Domain Service に `@Service` を付けないとしていたが、Bean として使う方法が書かれていなかった。
- 「必要に応じて」「必要が生じた場合だけ」のような条件付きの規則が多かった。

このリポジトリでは、AI エージェントが docs を読んで新しいクラスを作る。
条件付きの規則は、条件に当てはまるかの判断をエージェントに委ねるため、同じ役割のクラスがエージェントごとに別の形で作られやすい。
そこで、役割ごとに一つの形を決め、ArchUnit で機械的に検査できる規則にする必要がある。

クラス設計の標準、デファクトスタンダード、アンチパターンは、DDD とオニオンアーキテクチャの一次資料、CQRS の文献、Spring と Spring Modulith の公式文書、公開サンプルで調べた。
検討した論点は Alternatives Considered に示す。

## Decision

機能モジュールのクラスを次の役割に分け、置き場所、命名、形を一つに決める。
例には注文（`ordering`）モジュールを使う。

### モジュールルート

- モジュールルート（`com.example.demo.<feature>`）には、record、enum、`<Feature>Queries` インタフェースだけを置く。
- ルートの型は、`String`、`Instant`、`BigDecimal` などの Java の標準型と、同じルートパッケージの型だけを持つ。
- `<Feature>Queries` は、他モジュールから読まれるかどうかにかかわらず、すべての機能モジュールに作る。自モジュールの Controller もこれを使う。
- ルートの record は、参照の結果（`OrderDetails`）、検索条件（`OrderSearchCriteria`）、状態の変化を通知するイベント（過去形の `OrderPlaced`、`OrderConfirmed`）である。

### Domain

- `domain.model` には、集約、Entity、値オブジェクト、`<Aggregate>Repository` インタフェース、外部システムのインタフェース（`PaymentGateway`）を置く。DDD とオニオンアーキテクチャに従い、インタフェースをドメインの語彙で `domain.model` に定義し、実装を Infrastructure に置く。ヘキサゴナルアーキテクチャの port は使わず、`application.port` を廃止する。
- `domain.model` は Spring、jOOQ、JPA、Jackson に依存しない。
- 業務規則は、まず値オブジェクトか Entity（集約を含む）に置く。Domain Service は、複数の集約にまたがる規則、どの集約にも自然に属さない計算、Repository を使って確かめる規則（「未出荷の注文は3件まで」）の3つの場合に限って作る。
- Domain Service は `domain.service` に置き、Spring の `@Service` を付ける。`@Service` は Domain に許す唯一の Spring の型であり、ArchUnit の許可リストで検査する。Domain Service は Repository を引数で受け取ってよく、イベント発行、外部呼び出し、ログ出力は行わない。
- 注文の集約の状態は、受付、確定、支払い済み、出荷、取消とし、取消は受付のときだけできる。
  確定の後は決済が非同期で進み、確定の注文の取消を許すと、請求と取消が競合したときに返金が要るためである。
  返金はこの ADR の例では扱わない。

### Application

- 状態を変えるユースケースごとに、`<UseCase>CommandHandler` を一つ作る。ユースケース名はユビキタス言語の動詞にする（`PlaceOrder`、`CancelOrder`）。
- CommandHandler の public メソッドは `@Transactional` を付けた `handle(<UseCase>Command)` だけにし、`<UseCase>Result` を返す。
- `<UseCase>Command` と `<UseCase>Result` は、返す値がなくても必ず作り、標準型だけを持つ record として `application` に置く。
  Result は少なくとも集約の識別子を持つ。
  コマンドクエリ分離ではコマンドは値を返さないが（[Fowler, CommandQuerySeparation](https://martinfowler.com/bliki/CommandQuerySeparation.html)）、作成の応答に `Location` を組み立てるには識別子が要るためである。
  例外として、既存の集約を変える Command は `shared.concurrency` の `ExpectedLockNo` を持ち、`VersionedCommand` を実装する。
- CommandHandler は別の CommandHandler を呼ばない。
  一つのユースケースを一つのトランザクションで進めるという CommandHandler の定義を保ち、ユースケースが別のユースケースを呼んで連鎖する形を防ぐためである。
- 画面から呼ばれる CommandHandler は外部システムを呼ばず、状態を変えて `update` で保存し、ルートのイベント（`OrderConfirmed`）を発行して終える。
  外部システムは、そのイベントを自モジュールの `<Event>Listener`（`OrderConfirmedListener`）で受け、Listener が呼ぶ CommandHandler（`ChargeOrderCommandHandler`）が `PaymentGateway.charge` で呼ぶ。
  理由は二つある。
  一つ目に、確定の `handle` の中で請求すると、遅い外部の呼び出しの間、トランザクションと DB の接続を保ち、ロックを取った後に呼べば行のロックも保つ。
  二つ目に、ロールバックしても外部の副作用は戻らない。
  `ensureLockNo` は画面の値と読んだ値を比べるだけなので、同じ `lockNo` の確定が二つ同時に届くと両方が請求まで進む。
  後の一方は `update` の UPDATE の条件の `lock_no` で競合してロールバックするが、請求は残り、顧客に二重に請求する。
  イベント出版レジストリは業務データの更新と同じトランザクションでイベントを記録するトランザクションアウトボックスであり（[メッセージングの設計](../integration/async-messaging-design.md)の「DB 更新とメッセージ発行の整合」）、コミットした確定のイベントだけが決済へ渡る。
- 外部システムを呼ぶ CommandHandler は二層で冪等にする。
  外部システムの操作に冪等性キー（注文 ID）を渡し、集約がすでにその操作を終えていれば（`order.isPaid()`）何もせずに Result を返す。
  レジストリは at-least-once で配信し、未完了のイベント出版を再投入すると同じイベントが再び届くためである（[メッセージングの設計](../integration/async-messaging-design.md)の「配信保証」、[順序保証と冪等性](../integration/async-ordering-and-idempotency.md)）。
  請求に失敗したイベント出版はレジストリに `FAILED` で残り、[非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)の失敗した出版の再投入の手順で再投入する。
  自動の再投入は [issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108) で扱う。
- 外部システムを呼ぶ CommandHandler も、`@ApplicationModuleListener` が開くトランザクションの中で外部システムを呼ぶ。
  ただし、呼んでいる間は行をロックせず（行のロックは呼んだ後の `update` の UPDATE が取る）、画面の要求を待たせない。
  トランザクションの長さは Client のタイムアウト（[ADR-019](ADR-019-define-resilience-and-capacity-guardrails.md)）で抑え、ロールバックで戻らない請求は上の冪等性で二重にしない。
  このため、`charge` の `retry` は一回のままにし、再試行は再投入に任せる。
- 参照は機能ごとに一つの `<Feature>QueryService` が担う。QueryService はルートの `<Feature>Queries` を実装し、Repository で集約を読んでルートの record に変換する。参照専用の port は作らず、jOOQ で読み取りモデルへ直接射影しない。public メソッドには `@Transactional(readOnly = true)` を付ける。
- 他モジュールのイベントと、外部システムを呼ぶための自モジュールのイベントは、受信側モジュールの `application` に置く `<Event>Listener` が `@ApplicationModuleListener` を付けた `on` メソッドで受ける。Listener はちょうど一つの CommandHandler を呼ぶ。
- イベントは CommandHandler が `ApplicationEventPublisher` で発行する。`<Event>Publisher` クラス、`domain.model` の Domain Event、`infrastructure.messaging` は作らない。
- `application` の `@Service` は、`*CommandHandler`、`*QueryService`、`*Listener` のどれかの名前にする。

### Presentation と Infrastructure

- Controller は集約ごとに `<Aggregate>Controller` として `presentation.web` に置く。
- リクエストボディを受けるユースケースごとに `<UseCase>Request` を、参照の結果ごとに `<QueryResult>Response` を、`presentation.web` の record として置く。
- 作成の成功は 201 と、作成したリソースの URI を示す `Location` で返す。`POST` の対象 URI はコレクションなので、`Location` がないと作成したリソースを示せない（[RFC 9110 15.3.2](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.2)）。Spring では `ResponseEntity.created(URI)` と `ServletUriComponentsBuilder` で組み立てる（[Javadoc: ResponseEntity](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ResponseEntity.html)、[Spring, URI Links](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-uri-building.html)）。
- Request から Command への変換は Request のインスタンスメソッド `toCommand(...)`（パス変数の値は引数で受け取る）に、ルートの record から Response への変換は Response の static メソッド `from(...)` に置き、`presentation.web` に Mapper クラスを作らない。
- Presentation は Domain に依存せず、Application の Command、Result、CommandHandler と、ルートの Queries と record だけを使う。
  Command と Result を標準型だけにするのと同じ考え方で、Domain の型を Application の外へ出さず、HTTP API の形と Domain を独立に変えられるようにするためである。
- Repository の実装は `infrastructure.persistence` の `Jooq<Aggregate>Repository` とし、jOOQ の生成型と Domain の型の変換もこのクラスに書く。変換のための Mapper のクラスは作らない。
- 読み取りは、`select` に並べた列を `Field.convertFrom` で値オブジェクトと enum に変え、子の Entity を `multiset` の副問い合わせで同じ SQL で読み、`Records.mapping(Order::restore)` で集約にする（[jOOQ, Ad-hoc converters](https://www.jooq.org/doc/latest/manual/sql-execution/fetching/ad-hoc-converter/)、[jOOQ, The MULTISET value constructor](https://www.jooq.org/doc/latest/manual/sql-building/column-expressions/multiset-value-constructor/)）。jOOQ の作者も、一対多の対応づけにこの形を示している（[Stack Overflow, Mapping a one-to-many relationship to a list of records in jOOQ](https://stackoverflow.com/a/71855430/521799)）。列の数や型が `restore` や Entity のコンストラクタの引数と合わないと、コンパイルが失敗する。
- Repository の書き込みは、新しい集約の `add` と、既存の集約の `update` に分ける。
  新規と更新を一つの `save` にすると、実装は行の有無で INSERT と UPDATE を選ぶことになり、他の人が消した集約の更新が新しい行の作成になって、「行がない」（404）として返せないためである。
- `add` は、`insertInto` の `set(列, 値)` で業務の全列を書き、`lock_no` を含む共通カラムの値は [ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) の `shared` の共通処理から受け取る。
- `update` は、[PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)の楽観的ロックに従う（[ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
  集約ルートの行は `shared` の `TableWriter.updateCheckingVersion` に、集約の `lockNo` と業務の列の値を渡して先に更新し、子の行は戻り値の `LockedRoot` で後から更新する。
  版の条件、版の設定、件数の判定は `TableWriter` が持ち、0 件のとき、行がなければ `shared.failure` の `NotFoundException` を、行があれば `shared.concurrency` の `ConflictException` を投げる。
  `lock_timeout` までに行のロックを取れないときも、`TableWriter` が `ConflictException` に変える。
- Controller が作り、既存の集約の状態を変える Command は、クライアントが参照の応答で受け取った `lockNo` を `ExpectedLockNo` にして持ち、`VersionedCommand` を実装する。
  Request が `@Min(1) long lockNo` を受け取り、`toCommand` で `ExpectedLockNo` に変換する。
  CommandHandler は、集約を取り出した直後に `order.ensureLockNo(command.expectedLockNo())` で集約の `lockNo` と比べ、画面から受け取った値と更新の時点の行の値の比較が成り立つようにする。
- 外部システムのインタフェースの実装は `infrastructure.client` の `<ExternalSystem>Client` とする。
- Infrastructure は、機能モジュールの型のうち同じモジュールの `domain.model` の型だけを使い、Application、Domain Service、モジュールルートの型に依存しない。
  オニオン規則は Adapter から内側への依存をすべて許すが、Adapter がユースケースを呼べると Presentation のほかに処理の入口ができ、トランザクション境界が Application の外にも広がるためである。

### モジュール間の連携

- モジュール間の連携は、ルートのイベントの発行と受信、ルートの `<Feature>Queries` による参照の二つに限る。
- 他モジュールの状態を同期で変更しない。同期の状態変更が必要に見えたら、作業者（AI エージェントを含む）は実装を止めて利用者に確認し、ADR を起こす。
- CommandHandler は内部パッケージの `application` に置くため、他モジュールから呼ぶと Spring Modulith の `ApplicationModules.verify()` で失敗する。
- [ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) の `com.example.demo.shared` は、機能モジュールではない唯一の技術的な共有モジュールである。
  `shared.infrastructure.persistence` を `@NamedInterface` で公開し、業務の概念を持たず、他のモジュールの `infrastructure.persistence` だけから使う。
  `shared` は機能モジュールの間の連携の手段ではなく、機能モジュールの間の連携はイベントと `<Feature>Queries` だけのままである。
  使う場所の制限は、ArchUnit の `sharedModuleIsUsedOnlyByPersistenceAdapters` で検査する。

### 既存コードと規約の書き方

- `error` モジュールの `presentation` パッケージを `presentation.web` へ移し、Controller の置き場所の規則に例外を作らない。
- 規約文書には条件付きの規則を書かない。空のパッケージを最初のクラスより先に作らないことだけを、固定の規則として残す。
- 上の規則は、`PackageByFeatureOnionArchitectureTest` と `ClassRoleArchTest` の ArchUnit 規則で検査する。各規則は、`archfixture` パッケージの規約どおりのフィクスチャで誤検出がないこと、違反フィクスチャを検出することを `ArchitectureRuleFixtureTest` で確かめる。

## Consequences

### Positive

- クラス名の接尾辞（`CommandHandler`、`QueryService`、`Listener`）だけで、更新、参照、イベント受信のどれかが分かる。`Service` の名前の衝突もなくなる。
- 役割ごとに形が一つなので、AI エージェントが例を写して新しいクラスを作るときに、判断の分岐が残らない。
- 規則の大半を ArchUnit と Spring Modulith で検査でき、レビューで見る項目が減る。
- jOOQ と集約の変換では、列の名前の変更と、列と引数の数や型の食い違いがコンパイルで見つかる。変換が Repository の中にあるため、`infrastructure.persistence` に置くクラスは集約ごとに一つで済む。
- トランザクション境界が Application の `handle`、QueryService の public メソッド、Listener の `on` に集まる。
- モジュール間で同期の状態変更が起きないため、モジュールごとの統合テスト（`@ApplicationModuleTest`）が成り立つ。
- 画面の確定と外部システムの呼び出しが別のトランザクションになり、競合して取り消された確定は請求まで進まない。

### Negative

- 状態を変えるユースケースごとに Command、CommandHandler、Result の3クラスができ、クラスの数が増える。
- Command と Result を返す値がなくても作るため、中身の少ない record が増える。
- 参照も Repository を通すため、一覧や集計で集約全体を読む無駄が出ることがある。画面ごとの最適な SQL は書けない。
- Domain Service のソースに `import org.springframework.stereotype.Service` が入り、Domain が Spring から完全には独立しない。
- PostgreSQL には MULTISET がなく、jOOQ は JSON の集約で模倣する。子の行が数千に及ぶ集約では、二つの SQL に分けて読むほうが速いことがある。規約は `multiset` に固定し、作業者は二つの SQL に分ける前に実装を止めて利用者に確認する。
- `Jooq<Aggregate>Repository` は変換を含むため、Mapper のクラスに分けた場合より長くなる。
- 他モジュールの状態を同期で変える設計は、ADR を経ない限り選べない。
- 決済は確定の応答より後に非同期で行うため、確定済みで未払いの注文が残りうる。
  請求に失敗したイベント出版は、人が再投入するまで残る（[issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108)）。
- 確定の後の取消を例から外したため、返金を伴う取消は別に設計する必要がある。
- カードの拒否のように請求が回復不能に失敗した注文は、確定のまま次の状態へ進めない。この失敗の扱いも、返金と同じく例の範囲の外とし、別に設計する。

### Neutral

- 業務上の失敗の例外と 404、409、422 の対応づけは [ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md) で決める。
  400 は入力検証の規約のままとする。
- QueryService の `@Transactional(readOnly = true)` は、読み取り専用のトランザクションで参照中の書き込みを DB に拒否させるために付ける。分離レベルは既定の READ COMMITTED のままなので、一つのメソッドの中の複数の SQL が同じスナップショットを見ることまでは保証しない。
- 参照の性能が Repository 経由で足りなくなったら、読み取りモデルへの直接射影を ADR で決め直す。

## Alternatives Considered

### 選択肢1: ヘキサゴナルの port と UseCase インタフェースを使う

- **Description**：buckpal のように、`application.port.in` に `<UseCase>UseCase` インタフェース、`application.port.out` にユースケースごとの出力 port を置き、`<UseCase>Service` で実装する（[buckpal](https://github.com/thombergs/buckpal)、[Hexagonal Architecture with Java and Spring](https://reflectoring.io/spring-hexagonal/)）。
- **Pros**：入口と出口の対称性が明確で、出力 port の形をユースケースごとに最小にできる。
- **Cons**：実装が一つしかないインタフェースは、抽象ではなくヘッダーインタフェースになりやすい（[Seemann, Interfaces are not abstractions](https://blog.ploeh.dk/2010/12/02/Interfacesarenotabstractions/)）。出力 port は Repository パターンではなく、DDD のように Repository をドメインの語彙で `domain` に置く形（[Evans, DDD Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)、[Palermo, The Onion Architecture: part 1](https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/)、[Microsoft, Designing the infrastructure persistence layer](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/infrastructure-persistence-layer-design)、[IDDD_Samples](https://github.com/VaughnVernon/IDDD_Samples)、[spring-restbucks](https://github.com/odrotbohm/spring-restbucks)）と混在すると、置き場所が規則の分岐になる。ADR-002 はオニオンアーキテクチャを選んでいる。

### 選択肢2: `<UseCase>Service` または `<UseCase>` と命名する

- **Description**：以前の docs の `<UseCase>Service`、または接尾辞なしの `<UseCase>`（`PlaceOrder`）にする。
- **Pros**：クラス名が短い。
- **Cons**：`<UseCase>Service` は Domain Service と名前が衝突する。接尾辞なしでは、更新か参照かイベント受信かが名前から分からず、ArchUnit で役割ごとの形を検査できない。CQRS の文献ではコマンドごとに専用の Handler を置く形が確立しており（[Microsoft, Implementing the microservice application layer](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/microservice-application-layer-implementation-web-api)、[Axon Framework, Command Handlers](https://docs.axoniq.io/axon-framework-reference/4.10/axon-framework-commands/command-handlers/)）、メソッド名を `handle` に固定すると名前の重複も起きない（[Seemann, Ports and fat adapters](https://blog.ploeh.dk/2025/04/01/ports-and-fat-adapters/)）。

### 選択肢3: Command に値オブジェクトを持たせる

- **Description**：buckpal のように、Command が `OrderId` や `Money` を持ち、コンストラクタで自己検証する。
- **Pros**：型で取り違えを防げ、Handler に入る前に検証が終わる。
- **Cons**：Command を作る Controller や Listener が Domain の型に依存し、Presentation を Domain から切り離せない。標準型だけを持つ Command は Vernon と Microsoft の形でもあり（[IDDD_Samples](https://github.com/VaughnVernon/IDDD_Samples)）、Clean Architecture も境界を越えるのは単純なデータ構造としている（[Martin, The Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)）。

### 選択肢4: Domain Service に `@Service` を付けず、`@Bean` で登録する

- **Description**：Domain Service を通常の Java クラスにし、`@Configuration` の `@Bean` メソッドで登録する。jMolecules の DDD アノテーションをビルド時に Spring のアノテーションへ変換する方法もある（[jMolecules](https://github.com/xmolecules/jmolecules)、[jMolecules integrations](https://github.com/xmolecules/jmolecules-integrations)）。
- **Pros**：Domain のソースに Spring が現れず、Clean Architecture のフレームワーク非依存を文字どおり守れる。
- **Cons**：`@Bean` も Spring への依存であり、依存が設定クラスへ移るだけである（[Spring, Basic Concepts: @Bean and @Configuration](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html)）。Domain Service を足すたびに設定の変更が要り、理由を知らない作業者には手間に見える。Spring の Javadoc は `@Service` を DDD の Service として説明しており（[Javadoc: @Service](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/stereotype/Service.html)）、Spring Boot はコンポーネントスキャンによる Bean の検出を勧めている（[Spring Beans and Dependency Injection](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html)、[Classpath Scanning and Managed Components](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)）。jMolecules は依存とビルドプラグインが増える。フレームワークを交換する予定はないため、`@Service` だけを許可リストで認める。

### 選択肢5: 参照を jOOQ で読み取りモデルへ直接射影する

- **Description**：参照は Domain を通らず、jOOQ でルートの record へ直接射影する薄い読み取り層にする（[Young, CQRS Documents](https://cqrs.files.wordpress.com/2010/11/cqrs_documents.pdf)、[Microsoft, Implementing reads/queries in a CQRS microservice](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/cqrs-microservice-reads)）。単純な参照は Repository、一覧や結合を伴う参照は直接射影と、参照ごとに選ぶ形も検討した。
- **Pros**：画面に合わせた SQL を書け、Repository に参照用のメソッドが積み上がらない。
- **Cons**：同じテーブルへの対応づけが集約の復元と参照の射影の二つになる。参照ごとに選ぶ形は、「単純な参照」の境界が人によって異なり、同じ機能に二つの方式が混ざる。CQRS は大半のシステムに複雑さを加えるという警告もある（[Fowler, CQRS](https://martinfowler.com/bliki/CQRS.html)）。クラスを更新と参照に分けるだけの最も単純な CQRS（[Microsoft, Applying simplified CQRS and DDD patterns](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/apply-simplified-microservice-cqrs-ddd-patterns)）から始め、性能が足りなくなったら決め直す。

### 選択肢6: Command をモジュールルートに置き、他モジュールから同期で呼ぶ

- **Description**：Command と Result をルートに置き、他モジュールがルートの Bean を同期で呼んで状態を変える。Spring Modulith は、他モジュールの Bean への依存を正規のモジュール間の依存として認めている（[Spring Modulith, Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)）。
- **Pros**：呼び出しの結果をその場で受け取れ、処理の流れを追いやすい。
- **Cons**：Spring Modulith はモジュール間の主な連携手段をイベントにするよう勧め、発行元が相手のモジュールを直接呼ぶ形を避けたい例として示している（[Spring Modulith, Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)）。Evans も集約の境界を越える更新は非同期で扱うとしている（[Evans, DDD Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)）。同期の更新を許すと、モジュール単位の統合テスト（[Spring Modulith, Integration Testing Application Modules](https://docs.spring.io/spring-modulith/reference/testing.html)）が成り立たない。参照までイベントで表す Event-Carried State Transfer は、同じプロセスと同じ DB のモジュラーモノリスでは複製の費用だけが残る（[Fowler, What do you mean by "Event-Driven"?](https://martinfowler.com/articles/201701-event-driven.html)）。

### 選択肢7: Domain Event を `domain.model` に置き、`<Event>Publisher` で発行する

- **Description**：Evans の DDD Reference は、ドメインで起きた出来事を Domain Event としてドメインモデルの一部に表すとしている（[Evans, DDD Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)）。Vernon の公開サンプルも、発行のための `DomainEventPublisher` を `domain.model` に置く（[IDDD_Samples, DomainEventPublisher](https://github.com/VaughnVernon/IDDD_Samples/blob/master/iddd_common/src/main/java/com/saasovation/common/domain/model/DomainEventPublisher.java)）。Spring Data では、集約ルートが `AbstractAggregateRoot` を継承するか `@DomainEvents` を付けたメソッドでイベントを記録し、Repository の `save` と `delete` のときに発行される（[Spring Data Commons, Publishing Events from Aggregate Roots](https://docs.spring.io/spring-data/commons/reference/repositories/core-domain-events.html)、[Javadoc: AbstractAggregateRoot](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/AbstractAggregateRoot.html)）。以前の docs は、これに加えて `<Event>Publisher` クラスが `infrastructure.messaging` から発行する形にしていた。
- **Pros**：集約の状態変化とイベントの生成を一か所にまとめられ、DDD の正典の形に沿う。
- **Cons**：モジュール間のイベントは他モジュールが読む公開契約であり、ルートの record にしないと Domain の型が他モジュールへ漏れる。Spring Data の仕組みは Spring Data の Repository の `save` と `delete` でだけ働くため、jOOQ で書く Repository では使えず、集約が Spring Data の型に依存して `domain.model` を Spring から独立させる規則にも反する。Spring Modulith の文書の例も、集約の状態を変えたあとに `@Service` から `ApplicationEventPublisher` でイベントを発行している（[Spring Modulith, Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)）。発行は `ApplicationEventPublisher` の一行で済み、ラッパーのクラスは間接層を増やすだけである。発行をトランザクションの内側に置くには、CommandHandler で発行するのが最も単純である。

### 選択肢8: Listener を `infrastructure.messaging` に置く

- **Description**：以前の docs のように、イベントの受信を transport の実装として Infrastructure に置く。
- **Pros**：外部ブローカーを導入したときの受信と置き場所が揃う。
- **Cons**：`@ApplicationModuleListener` は `@Async`、`REQUIRES_NEW` の `@Transactional`、`@TransactionalEventListener` をまとめたものであり（[Spring Modulith, Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)）、受信のメソッドがトランザクション境界になる。Infrastructure に置くと、「トランザクション境界は Application に置く」という規則に例外ができる。

### 選択肢9: Domain Service から Repository を使わせない

- **Description**：データの取得は CommandHandler が行い、Domain Service には取得した値を引数で渡す。
- **Pros**：Domain Service が純粋な計算になり、テストに Repository の代役が要らない。
- **Cons**：Evans の DDD Reference も Vernon の公開サンプルも、Domain Service が Repository を使うことを禁じていない（[IDDD_Samples](https://github.com/VaughnVernon/IDDD_Samples)）。「未出荷の注文は3件まで」のように Repository で数えて確かめる規則を Domain Service に置けないと、規則が CommandHandler へ漏れ、ドメインモデル貧血症に近づく（[Fowler, AnemicDomainModel](https://www.martinfowler.com/bliki/AnemicDomainModel.html)）。Application から依存を渡せば集約は Repository に頼らずに済むという指摘（[Vernon, Effective Aggregate Design Part II](https://www.dddcommunity.org/wp-content/uploads/files/pdf_articles/Vernon_2011_2.pdf)）は集約についてのものであり、Domain Service には当てはめない。

### 選択肢10: Response を機能ごとの `<Feature>Response` にする

- **Description**：`presentation.web` の応答を機能ごとの `<Feature>Response`（`OrderResponse`）にし、参照の形ごとに `OrderSummaryResponse` などへ分けてもよいとする。
- **Pros**：機能ごとの応答のクラスが少なくて済む。
- **Cons**：「分けてもよい」は、分けるかどうかの判断を作業者に残す条件付きの規則になる。ルートの参照の結果（`OrderDetails`、`OrderSummary`）ごとに `<QueryResult>Response` を一つ作れば、名前と変換元が一つに決まり、`from(...)` の引数の型も一つになる。

### 選択肢11: jOOQ の Record と集約の変換を専用の Mapper のクラスに分ける

- **Description**：`infrastructure.persistence` に static メソッドだけを持つ集約ごとの Mapper のクラスを置き、`selectFrom` で読んだ `OrdersRecord` の getter から集約を組み立て、集約から `OrdersRecord` を作る。
- **Pros**：Repository が SQL だけになり、短くなる。
- **Cons**：子の行を別の SQL で読んで集約ごとに分ける処理は Repository に残り、一つの集約の変換が二つのクラスに分かれる。`convertFrom`、`multiset`、`Records.mapping` を使えば、変換は `select` に並べる列の中に収まり、別のクラスに分ける中身が残らない。役割とクラスが集約ごとに一つずつ増えるだけである。

### 選択肢12: MapStruct で変換する

- **Description**：MapStruct の `@Mapper` インタフェースから、ビルド時に変換のクラスを生成する（[MapStruct](https://mapstruct.org/)）。
- **Pros**：リフレクションを使わず、生成されたコードで変換する。
- **Cons**：アノテーションプロセッサがビルドに一つ増える。値オブジェクトへの包み直しや `Order.restore` のようなファクトリメソッドは、結局手書きのメソッドで補う必要がある。Presentation の変換は `toCommand()` と `from(...)` の一行で足り、MapStruct が要る場所がない。

### 選択肢13: jOOQ のコード生成の `forcedTypes` と `Converter` で値オブジェクトに対応づける

- **Description**：コード生成の設定で、`ORDERS.ORDER_ID` などの列を `Converter` で `OrderId` などの値オブジェクトの型にして生成する（[jOOQ, Forced types](https://www.jooq.org/doc/latest/manual/code-generation/codegen-advanced/codegen-config-database/codegen-database-forced-types/)）。
- **Pros**：生成型の列が最初から値オブジェクトの型になり、クエリごとの `convertFrom` が要らない。
- **Cons**：共有の生成パッケージ `com.example.demo.jooq` が、各モジュールの内部パッケージ `domain.model` の型に依存し、モジュールの境界をまたぐ。ArchUnit の規則は生成型が Domain に依存しない向きを前提にしており、生成コードを検査対象から外しているため、この依存は規則でも見つからない。

### 選択肢14: リフレクションで列と項目を対応づける

- **Description**：jOOQ の `into(Class)`、`fetchInto(Class)`（内部は `DefaultRecordMapper`）、`Record.from(Object)`、`DSLContext.newRecord(Table, Object)` や、ModelMapper、Dozer で、列と項目を名前で自動で対応づける。
- **Pros**：変換のコードを書かずに済む。
  jOOQ の正式な機能であり、リフレクションの費用もキャッシュで小さい。
- **Cons**：列や項目の名前を変えたときの誤りがコンパイルで見つからず、実行時に値が欠けるか例外になる。
  jOOQ のマニュアルも、`Records.mapping` の形は型を検査し、リフレクションの形は型を強く検査しないと区別している（[jOOQ, Ad-hoc converters](https://www.jooq.org/doc/latest/manual/sql-execution/fetching/ad-hoc-converter/)）。
  集約を作るときに `Order.restore` と値オブジェクトの検証を通らず、合うコンストラクタがなければ private のフィールドへ直接書き込む。
  書き込みの `from(Object)` と `newRecord(Table, Object)` では、ドメインのフィールド名を変えると、その列だけが例外を出さずに保存されなくなり、データを失う。
  この方式そのものの欠陥ではなく、集約を `restore` で作ること、変換の書き方を `Records.mapping` の一通りにすること、機械で検査することと両立しないため却下する。
  集約を通さずに DTO へ直接読む参照系を導入するときは、この判断を見直す。

### 選択肢15: 確定の CommandHandler で、行をロックしてから外部システムを呼ぶ

- **Description**：確定の `handle` の中で、先に集約ルートの行を `lock_no` の条件付きの UPDATE でロックして `lockNo` を比べ、その後で `PaymentGateway.charge` を呼んで確定を保存する。
- **Pros**：一つのトランザクションで済み、確定の応答で決済の成否を返せる。
  同時の確定は、後の一方がロックで止まり、請求まで進まない。
- **Cons**：遅い外部の呼び出しの間、行のロックとトランザクションを保ち、その間の同じ注文への要求は `lock_timeout` まで待って競合になる。
  請求の後にコミットが失敗すると、ロールバックしても請求は残る。
  トランザクションアウトボックスなら、コミットした確定だけが請求へ進む。

## References

- [Eric Evans, Domain-Driven Design Reference](https://www.domainlanguage.com/wp-content/uploads/2016/05/DDD_Reference_2015-03.pdf)
- [Jeffrey Palermo, The Onion Architecture: part 1](https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/)
- [Alistair Cockburn, Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)
- [Robert C. Martin, The Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
- [Martin Fowler, AnemicDomainModel](https://www.martinfowler.com/bliki/AnemicDomainModel.html)
- [Martin Fowler, CQRS](https://martinfowler.com/bliki/CQRS.html)
- [Martin Fowler, CommandQuerySeparation](https://martinfowler.com/bliki/CommandQuerySeparation.html)
- [Martin Fowler, What do you mean by "Event-Driven"?](https://martinfowler.com/articles/201701-event-driven.html)
- [Greg Young, CQRS Documents](https://cqrs.files.wordpress.com/2010/11/cqrs_documents.pdf)
- [Vaughn Vernon, Effective Aggregate Design Part II](https://www.dddcommunity.org/wp-content/uploads/files/pdf_articles/Vernon_2011_2.pdf)
- [Mark Seemann, Interfaces are not abstractions](https://blog.ploeh.dk/2010/12/02/Interfacesarenotabstractions/)
- [Mark Seemann, Ports and fat adapters](https://blog.ploeh.dk/2025/04/01/ports-and-fat-adapters/)
- [Microsoft, Designing the infrastructure persistence layer](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/infrastructure-persistence-layer-design)
- [Microsoft, Applying simplified CQRS and DDD patterns in a microservice](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/apply-simplified-microservice-cqrs-ddd-patterns)
- [Microsoft, Implementing reads/queries in a CQRS microservice](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/cqrs-microservice-reads)
- [Microsoft, Implementing the microservice application layer using the Web API](https://learn.microsoft.com/en-us/dotnet/architecture/microservices/microservice-ddd-cqrs-patterns/microservice-application-layer-implementation-web-api)
- [Spring Modulith, Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
- [Spring Modulith, Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)
- [Spring Modulith, Integration Testing Application Modules](https://docs.spring.io/spring-modulith/reference/testing.html)
- [Spring Data Commons, Publishing Events from Aggregate Roots](https://docs.spring.io/spring-data/commons/reference/repositories/core-domain-events.html)
- [Spring Data Commons Javadoc, AbstractAggregateRoot](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/AbstractAggregateRoot.html)
- [Spring Framework Javadoc, @Service](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/stereotype/Service.html)
- [Spring Framework Javadoc, ResponseEntity](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ResponseEntity.html)
- [Spring Framework, Classpath Scanning and Managed Components](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)
- [Spring Framework, Basic Concepts: @Bean and @Configuration](https://docs.spring.io/spring-framework/reference/core/beans/java/basic-concepts.html)
- [Spring Framework, URI Links](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-uri-building.html)
- [Spring Boot, Spring Beans and Dependency Injection](https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html)
- [RFC 9110, 15.3.2. 201 Created](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.2)
- [Axon Framework, Command Handlers](https://docs.axoniq.io/axon-framework-reference/4.10/axon-framework-commands/command-handlers/)
- [Tom Hombergs, buckpal](https://github.com/thombergs/buckpal)
- [Tom Hombergs, Hexagonal Architecture with Java and Spring](https://reflectoring.io/spring-hexagonal/)
- [Vaughn Vernon, IDDD_Samples](https://github.com/VaughnVernon/IDDD_Samples)
- [Vaughn Vernon, IDDD_Samples: DomainEventPublisher](https://github.com/VaughnVernon/IDDD_Samples/blob/master/iddd_common/src/main/java/com/saasovation/common/domain/model/DomainEventPublisher.java)
- [Oliver Drotbohm, spring-restbucks](https://github.com/odrotbohm/spring-restbucks)
- [jMolecules](https://github.com/xmolecules/jmolecules)
- [jMolecules Technology Integrations](https://github.com/xmolecules/jmolecules-integrations)
- [jOOQ, Ad-hoc converters](https://www.jooq.org/doc/latest/manual/sql-execution/fetching/ad-hoc-converter/)
- [jOOQ, The MULTISET value constructor](https://www.jooq.org/doc/latest/manual/sql-building/column-expressions/multiset-value-constructor/)
- [jOOQ, Forced types](https://www.jooq.org/doc/latest/manual/code-generation/codegen-advanced/codegen-config-database/codegen-database-forced-types/)
- [Stack Overflow, Mapping a one-to-many relationship to a list of records in jOOQ（Lukas Eder の回答）](https://stackoverflow.com/a/71855430/521799)
- [MapStruct](https://mapstruct.org/)
- [ADR-002: package by feature とオニオンアーキテクチャ](ADR-002-package-by-feature-onion-architecture.md)
- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-019: 外部連携の耐障害性と容量制御を標準化する](ADR-019-define-resilience-and-capacity-guardrails.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの UPDATE と DELETE を TableWriter に集める](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)
- [PostgreSQL の排他制御](../database/postgresql-concurrency-control.md)
- [PostgreSQL の共通カラム](../database/postgresql-common-columns.md)
- [メッセージングの設計](../integration/async-messaging-design.md)
- [順序保証と冪等性](../integration/async-ordering-and-idempotency.md)
- [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)
