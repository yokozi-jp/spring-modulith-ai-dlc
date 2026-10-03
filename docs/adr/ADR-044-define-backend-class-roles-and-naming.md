---
type: ADR
title: 'ADR-044: バックエンドのクラスの役割と命名を定める'
description: 機能モジュールのクラスを CQRS の役割名（CommandHandler、QueryService、Listener）と DDD の役割（集約、値オブジェクト、Repository、Domain Service）に分け、置き場所、命名、モジュール間の連携、ArchUnit による検査を定める決定。
tags: [adr, backend, architecture, ddd, cqrs]
---

# ADR-044: バックエンドのクラスの役割と命名を定める

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
例には注文（`order`）モジュールを使う。

### モジュールルート

- モジュールルート（`com.example.demo.<feature>`）には、record、enum、`<Feature>Queries` インタフェースだけを置く。
- ルートの型は、`String`、`Instant`、`BigDecimal` などの Java の標準型と、同じルートパッケージの型だけを持つ。
- `<Feature>Queries` は、他モジュールから読まれるかどうかにかかわらず、すべての機能モジュールに作る。自モジュールの Controller もこれを使う。
- ルートの record は、参照の結果（`OrderDetails`）、検索条件（`OrderSearchCriteria`）、他モジュールへ通知するイベント（過去形の `OrderPlaced`）である。

### Domain

- `domain.model` には、集約、Entity、値オブジェクト、`<Aggregate>Repository` インタフェース、外部システムのインタフェース（`PaymentGateway`）を置く。DDD とオニオンアーキテクチャに従い、インタフェースをドメインの語彙で `domain.model` に定義し、実装を Infrastructure に置く。ヘキサゴナルアーキテクチャの port は使わず、`application.port` を廃止する。
- `domain.model` は Spring、jOOQ、JPA、Jackson に依存しない。
- 業務規則は、まず値オブジェクトか Entity（集約を含む）に置く。Domain Service は、複数の集約にまたがる規則、どの集約にも自然に属さない計算、Repository を使って確かめる規則（「未出荷の注文は3件まで」）の3つの場合に限って作る。
- Domain Service は `domain.service` に置き、Spring の `@Service` を付ける。`@Service` は Domain に許す唯一の Spring の型であり、ArchUnit の許可リストで検査する。Domain Service は Repository を引数で受け取ってよく、イベント発行、外部呼び出し、ログ出力は行わない。

### Application

- 状態を変えるユースケースごとに、`<UseCase>CommandHandler` を一つ作る。ユースケース名はユビキタス言語の動詞にする（`PlaceOrder`、`CancelOrder`）。
- CommandHandler の public メソッドは `@Transactional` を付けた `handle(<UseCase>Command)` だけにし、`<UseCase>Result` を返す。
- `<UseCase>Command` と `<UseCase>Result` は、返す値がなくても必ず作り、標準型だけを持つ record として `application` に置く。Result は少なくとも集約の識別子を持つ。コマンドクエリ分離ではコマンドは値を返さないが（[Fowler, CommandQuerySeparation](https://martinfowler.com/bliki/CommandQuerySeparation.html)）、作成の応答に `Location` を組み立てるには識別子が要るためである。
- CommandHandler は別の CommandHandler を呼ばない。
- 参照は機能ごとに一つの `<Feature>QueryService` が担う。QueryService はルートの `<Feature>Queries` を実装し、Repository で集約を読んでルートの record に変換する。参照専用の port は作らず、jOOQ で読み取りモデルへ直接射影しない。public メソッドには `@Transactional(readOnly = true)` を付ける。
- 他モジュールのイベントは、受信側モジュールの `application` に置く `<Event>Listener` が `@ApplicationModuleListener` を付けた `on` メソッドで受ける。Listener はちょうど一つの CommandHandler を呼ぶ。
- イベントは CommandHandler が `ApplicationEventPublisher` で発行する。`<Event>Publisher` クラス、`domain.model` の Domain Event、`infrastructure.messaging` は作らない。
- `application` の `@Service` は、`*CommandHandler`、`*QueryService`、`*Listener` のどれかの名前にする。

### Presentation と Infrastructure

- Controller は集約ごとに `<Aggregate>Controller` として `presentation.web` に置く。
- リクエストボディを受けるユースケースごとに `<UseCase>Request` を、参照の結果ごとに `<QueryResult>Response` を、`presentation.web` の record として置く。
- 作成の成功は 201 と、作成したリソースの URI を示す `Location` で返す。`POST` の対象 URI はコレクションなので、`Location` がないと作成したリソースを示せない（[RFC 9110 15.3.2](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.2)）。Spring では `ResponseEntity.created(URI)` と `ServletUriComponentsBuilder` で組み立てる（[Javadoc: ResponseEntity](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ResponseEntity.html)、[Spring, URI Links](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-uri-building.html)）。
- Presentation は Domain に依存せず、Application の Command、Result、CommandHandler と、ルートの Queries と record だけを使う。
- Repository の実装は `infrastructure.persistence` の `Jooq<Aggregate>Repository` とし、jOOQ の生成型と Domain の型の変換は `<Aggregate>RecordMapper` に置く。
- 外部システムのインタフェースの実装は `infrastructure.client` の `<ExternalSystem>Client` とする。

### モジュール間の連携

- モジュール間の連携は、ルートのイベントの発行と受信、ルートの `<Feature>Queries` による参照の二つに限る。
- 他モジュールの状態を同期で変更しない。同期の状態変更が必要に見えたら、作業者（AI エージェントを含む）は実装を止めて利用者に確認し、ADR を起こす。
- CommandHandler は内部パッケージの `application` に置くため、他モジュールから呼ぶと Spring Modulith の `ApplicationModules.verify()` で失敗する。

### 既存コードと規約の書き方

- `error` モジュールの `presentation` パッケージを `presentation.web` へ移し、Controller の置き場所の規則に例外を作らない。
- 規約文書には条件付きの規則を書かない。空のパッケージを最初のクラスより先に作らないことだけを、固定の規則として残す。
- 上の規則は、`PackageByFeatureOnionArchitectureTest` と `ClassRoleArchTest` の ArchUnit 規則で検査する。各規則は、`archfixture` パッケージの規約どおりのフィクスチャで誤検出がないこと、違反フィクスチャを検出することを `ArchitectureRuleFixtureTest` で確かめる。

## Consequences

### Positive

- クラス名の接尾辞（`CommandHandler`、`QueryService`、`Listener`）だけで、更新、参照、イベント受信のどれかが分かる。`Service` の名前の衝突もなくなる。
- 役割ごとに形が一つなので、AI エージェントが例を写して新しいクラスを作るときに、判断の分岐が残らない。
- 規則の大半を ArchUnit と Spring Modulith で検査でき、レビューで見る項目が減る。
- トランザクション境界が Application の `handle`、QueryService の public メソッド、Listener の `on` に集まる。
- モジュール間で同期の状態変更が起きないため、モジュールごとの統合テスト（`@ApplicationModuleTest`）が成り立つ。

### Negative

- 状態を変えるユースケースごとに Command、CommandHandler、Result の3クラスができ、クラスの数が増える。
- Command と Result を返す値がなくても作るため、中身の少ない record が増える。
- 参照も Repository を通すため、一覧や集計で集約全体を読む無駄が出ることがある。画面ごとの最適な SQL は書けない。
- Domain Service のソースに `import org.springframework.stereotype.Service` が入り、Domain が Spring から完全には独立しない。
- 他モジュールの状態を同期で変える設計は、ADR を経ない限り選べない。

### Neutral

- 業務例外を HTTP の 400、404、422 に対応づける仕組みは、この ADR では決めない。いまは `@Valid` の失敗が 400、`ResponseStatusException` がそのステータスになり、Domain や CommandHandler が投げる JDK の例外は 500 になる。ユースケースが Domain の例外を 400、404、422 で返す必要が出たら、作業者は利用者に確認し、対応づけを新しい ADR で決める（[ADR-013](ADR-013-standardize-http-api-contracts.md)）。
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

- **Description**：集約がイベントを記録し、`<Event>Publisher` クラスが `infrastructure.messaging` から発行する。
- **Pros**：集約の状態変化とイベントの生成を一か所にまとめられる。
- **Cons**：モジュール間のイベントは他モジュールが読む公開契約であり、ルートの record にしないと Domain の型が他モジュールへ漏れる。発行は `ApplicationEventPublisher` の一行で済み、ラッパーのクラスは間接層を増やすだけである。発行をトランザクションの内側に置くには、CommandHandler で発行するのが最も単純である。

### 選択肢8: Listener を `infrastructure.messaging` に置く

- **Description**：以前の docs のように、イベントの受信を transport の実装として Infrastructure に置く。
- **Pros**：外部ブローカーを導入したときの受信と置き場所が揃う。
- **Cons**：`@ApplicationModuleListener` は `@Async`、`REQUIRES_NEW` の `@Transactional`、`@TransactionalEventListener` をまとめたものであり（[Spring Modulith, Working with Application Events](https://docs.spring.io/spring-modulith/reference/events.html)）、受信のメソッドがトランザクション境界になる。Infrastructure に置くと、「トランザクション境界は Application に置く」という規則に例外ができる。

### 選択肢9: Domain Service から Repository を使わせない

- **Description**：データの取得は CommandHandler が行い、Domain Service には取得した値を引数で渡す。
- **Pros**：Domain Service が純粋な計算になり、テストに Repository の代役が要らない。
- **Cons**：Evans の DDD Reference も Vernon の公開サンプルも、Domain Service が Repository を使うことを禁じていない（[IDDD_Samples](https://github.com/VaughnVernon/IDDD_Samples)）。「未出荷の注文は3件まで」のように Repository で数えて確かめる規則を Domain Service に置けないと、規則が CommandHandler へ漏れ、ドメインモデル貧血症に近づく（[Fowler, AnemicDomainModel](https://www.martinfowler.com/bliki/AnemicDomainModel.html)）。Application から依存を渡せば集約は Repository に頼らずに済むという指摘（[Vernon, Effective Aggregate Design Part II](https://www.dddcommunity.org/wp-content/uploads/files/pdf_articles/Vernon_2011_2.pdf)）は集約についてのものであり、Domain Service には当てはめない。

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
- [Oliver Drotbohm, spring-restbucks](https://github.com/odrotbohm/spring-restbucks)
- [jMolecules](https://github.com/xmolecules/jmolecules)
- [jMolecules Technology Integrations](https://github.com/xmolecules/jmolecules-integrations)
- [ADR-002: package by feature とオニオンアーキテクチャ](ADR-002-package-by-feature-onion-architecture.md)
- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
