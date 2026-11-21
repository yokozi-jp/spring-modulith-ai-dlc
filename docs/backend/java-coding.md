---
type: Convention
title: バックエンドの Java 実装規約
description: 手書きのバックエンドコードで使う Lombok、record、Domain Model の状態変更、ロギング、依存の注入、例外、assert、非推奨の API、プロキシで動くアノテーション（自己呼び出し、private、final、static、@Async と @Scheduled のメソッドの形、レビューで見る点）、警告の抑制の規約と理由を定める。データキャリアやログ出力を書くとき、Bean の依存や例外を書くとき、@Transactional、@Async、@Scheduled などを付けるとき、@SuppressWarnings を書くとき、これらの PMD や ArchUnit の規則で失敗したときに読む。
tags: [convention, backend, java, logging]
---

# バックエンドの Java 実装規約

Lombok の `@Data` と `@Setter` を使わず、データキャリアは record にし、Domain Model の状態は業務上の操作を表すメソッドで変更する。
ロギングは SLF4J facade と Lombok の `@Slf4j` に統一し、依存はコンストラクタで受け取り、具体的な例外を投げ、`assert` に詳細を書き、非推奨の API を使わない。
プロキシで動くアノテーションを付けたメソッドは、同じクラスの中から呼ばず、`private`、`final`、`static` にしない。
`@Async` のメソッドは `void` か `Future` を返し、`@Scheduled` のメソッドは引数のない `void` のメソッドにする。
`@SuppressWarnings` には、警告を出すツールごとのトークンを書く。
これらの規則の検査方法は [バックエンドのアーキテクチャテスト](architecture-tests.md) に示す。

## 規約

手書きのバックエンドコードでは、Lombok の `@Data` と `@Setter` を使用しない。

次のデータキャリアには record を使う。

- モジュールルートの参照の結果、検索条件、イベント。
- 値オブジェクト。固定の値の集合は enum にする。
- Application の `<UseCase>Command` と `<UseCase>Result`。
- Presentation の `<UseCase>Request` と `<QueryResult>Response`。

Domain Model の状態は setter で公開せず、業務上の操作と不変条件を表すメソッドを介して変更する。

## ロギング

機能コードのロギングには SLF4J facade を使い、Logback、Log4j、Apache Commons Logging の実装 API を直接参照しない。

ログを出力する手書きクラスには Lombok の `@Slf4j` を付け、Logger フィールドと `LoggerFactory` を直接記述しない。

`@Slf4j` の使用規約はソース上の Logger 型と `LoggerFactory` 参照を PMD で禁止して強制する。

ベースパッケージ直下のロギング基盤設定は実装 API との接続を担うため、ArchUnit の facade 制約だけは対象外とする。

ログの書き方を SLF4J と `@Slf4j` の一通りにする。
SLF4J の key-value は、OpenTelemetry へ送るログの属性になる（[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)）。
機能コードが SLF4J の API だけを使えば、ロギングの実装を差し替えても機能コードを変えずに済む。

## 依存の注入

Bean の依存はコンストラクタの引数で受け取り、フィールドとメソッドに `@Autowired` などの注入アノテーションを付けない。
コンストラクタが一つなら `@Autowired` を付けない。

依存をコンストラクタに明示すると、フィールドを `final` にでき、テストでは Spring を起動せずに組み立てられる。
Spring の文書もコンストラクタによる注入を勧めている（[Spring Framework, Constructor-based or setter-based DI?](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-vs-setter-injection)）。

## 例外の型

`Throwable`、`Exception`、`RuntimeException`、`Error` を投げず、具体的な例外を投げる。

- 業務上の失敗は、`shared.failure` の `NotFoundException`（404）、`BusinessRuleViolationException`（422）と、`shared.concurrency` の `ConflictException`（409）の 3 つの型で投げる（[業務上の失敗の例外](class-roles/business-exception.md)）。
- プログラムの誤りは `IllegalStateException` と `IllegalArgumentException` で投げ、HTTP では 500 になる。
- `NoSuchElementException` の生成と引数なしの `Optional.orElseThrow()` は、ArchUnit の `noSuchElementExceptionIsNotThrown` が禁じる。
- 4 つ目の業務上の失敗の型は ADR で決める（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。

呼び出し側と `error.presentation.web.ApiExceptionHandler` が例外の型で失敗を区別し、HTTP の応答に対応づけられるようにするためである（[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)）。

例外に `@ResponseStatus` を付けない。
`ApiExceptionHandler` の `@ExceptionHandler(Exception.class)` が `ResponseStatusExceptionResolver` より先に例外を受け取るため、注釈したステータスは使われず 500 になる。

## assert 文のメッセージ

`assert` 文と `new AssertionError()` には、対象と値を示す詳細のメッセージを付ける。

失敗のログだけで原因と値を特定できるようにするためである。

## 非推奨の API

`@Deprecated` の API を使わず、その Javadoc が示す代わりの API を使う。

依存ライブラリの更新で API が削除されても、ビルドが壊れないようにするためである。
Dependabot の minor と patch の更新はまとめて取り込む（[ADR-021](../adr/ADR-021-group-dependabot-minor-and-patch-updates.md)）。

## プロキシで動くアノテーション

次のアノテーションを付けたメソッドは、同じクラスの中から呼ばず、`private`、`final`、`static` にしない。
これらをメタアノテーションに持つ `@ApplicationModuleListener` のような合成アノテーションも、同じに扱う。

- Spring の `@Transactional`、`@Async`、`@Cacheable`、`@CachePut`、`@CacheEvict`。
- Spring Framework の `@Retryable`、`@ConcurrencyLimit`（`org.springframework.resilience.annotation`）。
- Spring Security の `@PreAuthorize`、`@PostAuthorize`、`@PreFilter`、`@PostFilter`、`@Secured`。
- Resilience4j の `@CircuitBreaker`、`@Retry`、`@RateLimiter`、`@Bulkhead`、`@TimeLimiter`。

いまのコードで使っていないアノテーションも、検査の費用がほぼなく使い始めたときの事故を防げるため一覧に残す。
この一覧を変えるときは、同じ変更で `ProxyRulesArchTest` の `INTERCEPTED_ANNOTATIONS` も変える。

Spring の AOP プロキシは、Bean の外からの呼び出しだけを横取りする。
同じクラスの中からの呼び出しはプロキシを通らないため、アノテーションは黙って効かない（[Spring Framework, Understanding AOP Proxies](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html#aop-understanding-aop-proxies)）。
トランザクションが始まらず、非同期にならず、キャッシュを通らず、認可を確かめず、リトライ、同時実行数の制限、Resilience4j のサーキットブレーカーが働かない。

Bean の外から呼んでも、`private`、`final`、`static` のメソッドではアノテーションが効かない。
Spring Boot は既定で CGLIB のプロキシを使い、Bean のクラスのサブクラスでメソッドを上書きして横取りするが、これらのメソッドは上書きできないためである（[Spring Framework, Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)）。
`final` のメソッドはプロキシのインスタンスの上でそのまま動き、注入したフィールドが `null` のまま使われる。
package-private と `protected` のメソッドは CGLIB のプロキシが上書きできるため、この規則では禁じない（`@Transactional` は、別の規則で Application の public メソッドに限る）。

アノテーションを付けたメソッドは別の Bean へ移し、その Bean を注入して外から呼ぶ。

アノテーションがなくても、モジュールの public な Bean クラス（`@Component` をメタアノテーションに持つ `@Service`、`@Repository`、`@Controller` など）の public メソッドに `final` を付けない。
Spring Modulith の observability（`spring-modulith-starter-insight`）が、モジュールの public な Bean をアノテーションの有無によらず CGLIB のプロキシにする。
`final` のメソッドはプロキシのインスタンスの上でそのまま動き、注入したフィールドが `null` のため `NullPointerException` になる。
クラスを `new` で作る単体テストと起動は通り、Spring を起動する統合テストだけが失敗する。
package-private のクラスはこの observability のプロキシにならなかったが（`EventPublicationResubmitter` など）、public にしたときに壊れるため、規則は public のクラスだけを見る。

`@Async` のメソッドは `void` か `Future` を返す。
Spring は、戻り値の型が `void` と `Future` 以外の `@Async` のメソッドを、呼ぶたびに `IllegalArgumentException` で拒否する。
`void` の `@Async` が投げた例外は呼び出し元に届かず、既定の `AsyncUncaughtExceptionHandler` が ERROR のログに出すだけである。
結果や失敗を呼び出し元で扱うときは、`CompletableFuture` を返す。

`@Scheduled` のメソッドは、引数のない `void` のメソッドにし、`private` と `final` にしない。
スケジューラは、`@Scheduled` のメソッドの戻り値を呼び出し元に返す値として使わず、`Publisher` に変換できない型の戻り値は捨てる。
Reactor がクラスパスにあるいまの構成では、スケジューラは `Mono` などの `Publisher` を返すメソッドを登録のときに一度だけ呼び、返した `Publisher` を実行のたびに購読する（[Spring Framework, Scheduled](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/annotation/Scheduled.html)）。
戻り値の型でメソッドの呼ばれ方が変わるため、戻り値は `void` に限る。
引数のある `@Scheduled` のメソッドがあると、起動に失敗する。
Bean が AOP のプロキシになると、`private` の `@Scheduled` のメソッドも起動に失敗し、`final` のメソッドは注入したフィールドが `null` のプロキシの上で動く。
Spring はプロキシの Bean でも `static` の `@Scheduled` を呼べるため、`static` は禁じない。
実行の結果はログかメトリクスに出す（`EventPublicationResubmitter.resubmitOnce`）。

`@Around` などの AspectJ の advice と `@Pointcut` の式には `within(<パッケージ>..*<役割名>)` を書き、`execution` にはメソッドの名前と修飾子だけを書く。
`execution` の宣言型だけで対象を絞ると、AspectJ がすべての Bean のすべてのメソッドで上位型をたどって照合し、Spring のコンテキストの起動が数倍遅くなる（[ADR-051](../adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。

`@Aspect` のクラスには `Ordered.LOWEST_PRECEDENCE` より小さい値の `@Order`（`@Order(Ordered.LOWEST_PRECEDENCE - 100)` など）を付け、すべての Aspect をトランザクションより外側で動かす（[ADR-051](../adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。
値のない `@Order` は使わない。
トランザクションの advisor の順序を既定の `Ordered.LOWEST_PRECEDENCE` に保つため、`@EnableTransactionManagement` を宣言しない。
トランザクションの内側で動く Aspect が必要になったら、先に ADR-051 とこの規則を変える。

### レビューで見る点

ArchUnit の規則が検出しない書き方とその理由は、[バックエンドのアーキテクチャテスト](architecture-tests.md)の「プロキシの規則が検出しない書き方」に示す。
レビューでは次を見る。

- メソッド参照（`this::archive`）と内部クラスから、同じクラスのアノテーションを付けたメソッドを呼んでいない。
- クラスに付けた `@Async`、`@Cacheable`、`@CachePut`、`@CacheEvict`、`@Retryable`、`@ConcurrencyLimit`、Spring Security、Resilience4j のアノテーションは、そのクラスのメソッドに付けたものとして、自己呼び出し、`private`、`final`、`static`、`@Async` の戻り値を見る。
- `void` の `@Async` が投げる例外を、メソッドの中で受けて記録するか、`AsyncUncaughtExceptionHandler` で回復する。
- `@Retryable` や `@CircuitBreaker` が、トランザクションの内側と外側のどちらで動くかが意図どおりである。
- `@Transactional` の伝播（`propagation`）とロールバックの条件が意図どおりである。
  検査例外では、既定でロールバックしない。

## 警告の抑制

`@SuppressWarnings` には、警告を出すツールのトークンを書き、ツールごとに必要なものだけを並べる。

| ツール                   | 未使用の private メソッドのトークン |
| ------------------------ | ----------------------------------- |
| Eclipse JDT（VSCode）    | `"unused"`                          |
| Error Prone              | `"UnusedMethod"`                    |
| PMD                      | `"unused"` で抑制される             |

PMD は `"unused"` を `PMD.UnusedPrivateMethod` の抑制として扱う。
`"unused"` と `"PMD.UnusedPrivateMethod"` を両方書くと、PMD の `UnnecessaryWarningSuppression` が後者を不要として失敗させる。

型引数のない型（raw type）を返すメソッドをチェーンでつなぐと、javac は `[unchecked]` を警告する（`-Xlint:rawtypes,unchecked`）。
警告を抑制せず、戻り値を型引数付きの変数に受けて、セッターで設定する。
