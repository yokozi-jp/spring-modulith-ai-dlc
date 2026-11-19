---
type: Convention
title: バックエンドの Java 実装規約
description: 手書きのバックエンドコードで使う Lombok、record、Domain Model の状態変更、ロギング、依存の注入、例外、assert、非推奨の API、プロキシで動くアノテーション（自己呼び出し、private、final、static、@Async と @Scheduled のメソッドの形、ArchUnit が検出しない誤り）、警告の抑制の規約と理由を定める。データキャリアやログ出力を書くとき、Bean の依存や例外を書くとき、@Transactional、@Async、@Scheduled などを付けるとき、@SuppressWarnings を書くとき、これらの PMD や ArchUnit の規則で失敗したときに読む。
tags: [convention, backend, java, logging]
---

# バックエンドの Java 実装規約

Lombok の `@Data` と `@Setter` を使わず、データキャリアは record にし、Domain Model の状態は業務上の操作を表すメソッドで変更する。
ロギングは SLF4J facade と Lombok の `@Slf4j` に統一し、依存はコンストラクタで受け取り、具体的な例外を投げ、`assert` に詳細を書き、非推奨の API を使わない。
プロキシで動くアノテーションを付けたメソッドは、同じクラスの中から呼ばず、`private`、`final`、`static` にしない。
`@Async` のメソッドは `void` か `CompletableFuture` を返し、`@Scheduled` のメソッドは引数のない `void` のメソッドにする。
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

Spring の AOP プロキシは、Bean の外からの呼び出しだけを横取りする。
同じクラスの中からの呼び出しはプロキシを通らないため、アノテーションは黙って効かない（[Spring Framework, Understanding AOP Proxies](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html#aop-understanding-aop-proxies)）。
トランザクションが始まらず、非同期にならず、キャッシュを通らず、認可を確かめず、リトライ、同時実行数の制限、Resilience4j のサーキットブレーカーが働かない。

Bean の外から呼んでも、`private`、`final`、`static` のメソッドではアノテーションが効かない。
Spring Boot は既定で CGLIB のプロキシを使い、Bean のクラスのサブクラスでメソッドを上書きして横取りするが、これらのメソッドは上書きできないためである（[Spring Framework, Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)）。
`final` のメソッドはプロキシのインスタンスの上でそのまま動き、注入したフィールドが `null` のまま使われる。
package-private と `protected` のメソッドは CGLIB のプロキシが上書きできるため、この規則では禁じない（`@Transactional` は、別の規則で Application の public メソッドに限る）。

アノテーションを付けたメソッドは別の Bean へ移し、その Bean を注入して外から呼ぶ。

`@Async` のメソッドは `void` か `CompletableFuture` を返す。
Spring は、戻り値の型が `void` と `Future` 以外の `@Async` のメソッドを、呼ぶたびに `IllegalArgumentException` で拒否する。
`void` の `@Async` が投げた例外は呼び出し元に届かず、既定の `AsyncUncaughtExceptionHandler` が ERROR のログに出すだけである。
結果や失敗を呼び出し元で扱うときは、`CompletableFuture` を返す。

`@Scheduled` のメソッドは、引数のない `void` のメソッドにし、`private` と `final` にしない。
スケジューラは戻り値を捨て、引数のある `@Scheduled` のメソッドがあると起動に失敗する。
Bean が AOP のプロキシになると、`private` の `@Scheduled` のメソッドも起動に失敗し、`final` のメソッドは注入したフィールドが `null` のプロキシの上で動く。
Spring はプロキシの Bean でも `static` の `@Scheduled` を呼べるため、`static` は禁じない。
実行の結果はログかメトリクスに出す（`EventPublicationResubmitter.resubmitOnce`）。

`@Around` などの AspectJ の advice と `@Pointcut` の式には `within(<パッケージ>..*<役割名>)` を書き、`execution` にはメソッドの名前と修飾子だけを書く。
`execution` の宣言型だけで対象を絞ると、AspectJ がすべての Bean のすべてのメソッドで上位型をたどって照合し、Spring のコンテキストの起動が数倍遅くなる（[ADR-051](../adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。

`@Aspect` のクラスには `Ordered.LOWEST_PRECEDENCE` より小さい値の `@Order`（`@Order(Ordered.LOWEST_PRECEDENCE - 100)` など）を付け、すべての Aspect をトランザクションより外側で動かす（[ADR-051](../adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。
値のない `@Order` は使わない。
トランザクションの advisor の順序を既定の `Ordered.LOWEST_PRECEDENCE` に保つため、`@EnableTransactionManagement` を宣言しない。
トランザクションの内側で動く Aspect が必要になったら、先に ADR-051 とこの規則を変える。

いまのコードで使っていないアノテーションの規則も、検査の費用がほぼなく使い始めたときの事故を防げるため残す。

### ArchUnit が検出しない誤り

この節の規則は、ArchUnit が `task test` で検査する（規則名と違反フィクスチャは[バックエンドのアーキテクチャテスト](architecture-tests.md)の「共通実装とプロキシ」に示す）。
自己呼び出しの規則は、ラムダの中の呼び出しと継承したメソッドの呼び出しも検出し、同じクラスの別のインスタンスの呼び出しも、そのインスタンスがプロキシの Bean かを問わず拒否する。
次の誤りは ArchUnit の規則では検出しないため、レビューで見る。

- メソッド参照（`this::archive`）と、内部クラスから外側のクラスのメソッドを呼ぶ自己呼び出し。
  ArchUnit はメソッド参照を呼び出しとして数えず、内部クラスを別のクラスとして扱う。
- クラスに付けた `@Async`、`@Cacheable`、Spring Security、Resilience4j のアノテーション。
  規則はメソッドに付けたアノテーションだけを見るため、そのクラスのメソッドの自己呼び出し、`final` のメソッド、`@Async` の戻り値を検出しない。
- `void` の `@Async` が投げる例外の扱い。
  メソッドの中で例外を受けて記録するか、`AsyncUncaughtExceptionHandler` で回復するかは、メソッドの形からは分からない。
- 複数のプロキシの順序（`@Retryable` や `@CircuitBreaker` が、トランザクションの内側と外側のどちらで動くか）。
  `aspectsDeclareOrder` は、`@Aspect` のクラスとトランザクションの順序だけを見る。
- `@Transactional` の伝播（`propagation`）とロールバックの条件。
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
