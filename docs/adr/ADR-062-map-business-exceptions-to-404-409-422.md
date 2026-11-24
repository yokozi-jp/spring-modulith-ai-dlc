---
type: ADR
title: 'ADR-062: 業務上の失敗を 3 つの例外の型で表し、404、409、422 の Problem Details に対応づける'
description: 業務上の失敗を shared.failure の NotFoundException と BusinessRuleViolationException、shared.concurrency の ConflictException の 3 つの原因を持たない型に固定し、ApiExceptionHandler が 404 を about:blank、422 を理由ごと、409 を種類ごとの type の Problem Details にして INFO で記録し（409 は conflict.kind で種類を区別する）、23505 を TableWriter で原因を付けずに 409 に変え、JDK の例外の誤用を ArchUnit で禁じる決定。
tags: [adr, backend, web-api, error-handling]
---

# ADR-062: 業務上の失敗を 3 つの例外の型で表し、404、409、422 の Problem Details に対応づける

## Status

Proposed

## Date

2026-10-06

## Context

[ADR-050](ADR-050-define-backend-class-roles-and-naming.md) と [ADR-054](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md) は、楽観的ロックの競合を `shared.concurrency` の `ConflictException` で 409 にすると決めた。
一方、見つからない集約（404）と許されない状態遷移（422）の対応づけは決めずに残し、それまでは 500 のままにするとしていた。

その結果、main には次の問題があった。

- `TableWriter.updateCheckingVersion` と `deleteCheckingVersion` は、主キーの行がないときに `NoSuchElementException` を投げ、利用者には 500 で返る。
- 役割ごとの文書の例は、見つからない集約に `NoSuchElementException`、許されない状態遷移に `IllegalStateException` を投げており、写すと 500 になる。
- `IllegalStateException` は、許されない状態遷移とプログラムの誤り（`TableWriter` の 2 件以上の更新）の両方に使われていた。
- `LockedRoot.updateChild` は、`shared.persistence` から Spring Web の `ResponseStatusException` を投げて 422 にしていた。
- 一意制約の違反（`23505`）は、どこでも 409 に変換していなかった。
- `controller.md` は、見つからない参照を Controller の `ResponseStatusException(HttpStatus.NOT_FOUND)` で 404 にする例を示していた。

機能モジュールはまだ main になく、例外の型を決めても移行の費用は共通処理、テスト、文書に限られる。
API のエラー契約は [ADR-013](ADR-013-standardize-http-api-contracts.md) の RFC 9457 Problem Details、`title` の翻訳は [ADR-016](ADR-016-localize-api-and-spa-messages.md)、ログの扱いは [ADR-015](ADR-015-structure-and-protect-observability-data.md) に従う。

この版は [issue #145](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/145) を受けて決定を改めた。
最初の版は、`23505` と `55P03` の Spring の例外を原因に付けて `ConflictException` に変えていた。
`ApiExceptionHandler` は例外を付けて記録するため、409 の `exception.stacktrace` の `Caused by` に、jOOQ の SQL と PostgreSQL の `Key (...)=(...)` の入力値が出ていた。
これは、SQL と入力値をアプリから渡さないとする[可観測性の規約](../observability/conventions.md)と食い違う。
ADR は Proposed のままであるため、新しい ADR を起こさず、この版で決定を書き直す。

さらに、[issue #541](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/541) を受けて、422 と 409 の本文の決定を改めた。
前の版は 404、409、422 をすべて `about:blank` にしていたため、画面は 422 と 409 の理由を区別できなかった。
`Order` は 4 つの理由で 422 を投げるが、画面は操作ごとに 1 つの文しか出せず、テナントの最後の管理者の拒否も理由を示せなかった。
[#317](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/317) で、422 の例外に理由をモジュールごとの enum で渡すと決めた。
409 は、モジュールの理由を持たせず、`ConflictException.Kind` の種類ごとに共通の `type` を付けると [#309](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/309) で決めた。
この ADR は Proposed のままであるため、同じ版で Decision を書き直す。
`type` と `title` のキーの形は [ADR-125](ADR-125-derive-problem-types-from-failure-reasons.md) が決める。

## Decision

- 業務上の失敗は、次の 3 つの型だけで表す。
  4 つ目の型は ADR で決める。
  集約ごとの型を作らず、3 つの型は `final` にして継承させない。

  | 型                                              | 意味                                                             | HTTP |
  | ----------------------------------------------- | ---------------------------------------------------------------- | ---- |
  | `shared.failure.NotFoundException`              | 指定された集約や行が存在しない、または参照の権限がなく存在を隠す | 404  |
  | `shared.failure.BusinessRuleViolationException` | 許されない状態遷移、業務規則の違反、要求を今の状態へ適用できない | 422  |
  | `shared.concurrency.ConflictException`（既存）  | 版の不一致、ロック待ちの失敗、一意制約の違反                     | 409  |

- `NotFoundException` と `BusinessRuleViolationException` は、`shared` モジュールの新しいパッケージ `shared.failure` に置き、`@NamedInterface("failure")` で公開する。
  Spring と HTTP の型に依存せず、どの層からも使う。
  `ConflictException` は `shared.concurrency` から移さず、意味を一意制約の違反へ広げて Javadoc を直す。
- `BusinessRuleViolationException` は、理由（`shared.failure` の interface `FailureReason`）とログ用のメッセージを受け取り、メッセージだけのコンストラクタを持たない。
  理由は、モジュールごとの enum が `FailureReason` を実装して表す（例：`ordering` の `OrderFailure` の `NOT_DRAFT`、`PRODUCT_NOT_FOUND`、`PRODUCT_DISCONTINUED`、`NO_LINES`）。
  `FailureReason` は sealed にしない。
  名前のないモジュールでは、sealed の型の permits が同じパッケージに限られ、`javac` が止めるためである（#317 で確かめた）。
- `ApiExceptionHandler` は、3 つの型を次の Problem Details にする。
  `title` は `Accept-Language` の言語で返し、`detail` は付けない。
  例外のメッセージは応答に入れず、ログにだけ出す。

  | 型                               | HTTP | `type`                                        | `title` のキー                        |
  | -------------------------------- | ---- | --------------------------------------------- | ------------------------------------- |
  | `NotFoundException`              | 404  | `about:blank`                                 | `problem.title.404`                   |
  | `BusinessRuleViolationException` | 422  | 理由ごと（`/problems/<module>/<kebab-case>`） | `problem.title.<module>.<kebab-case>` |
  | `ConflictException`              | 409  | 種類ごと（`/problems/<kebab-case>`）          | `problem.title.<kebab-case>`          |

  404 は、対象がない場合と存在を隠す場合で本文を同じにするため、理由を分けず `about:blank` のままにする。
  409 の種類ごとの `type` は、`Kind.VERSION` が `/problems/version-conflict`、`Kind.LOCK` が `/problems/lock-timeout`、`Kind.UNIQUE` が `/problems/unique-violation` である。
  アプリのコードは `about:blank` の 422 と 409 を返さない。
  `ApiExceptionHandler` が理由を知らない Spring の 422 と 409 の例外と、`/error` に転送された 422 と 409 だけは、`about:blank` のまま返る。
  どちらもアプリのコードからは作れない（次の ArchUnit の規則）。
- ログは、404 と 422 を INFO の `API business failure`、409 を INFO の `API conflict` で、`http.response.status_code` と例外を付けて記録する。
  409 には、競合の種類（`version`、`lock`、`unique`）を属性 `conflict.kind` で足す。
  3 つの型は原因（cause）を持たない。
  コンストラクタは原因を `null` に決め、原因を受け取るコンストラクタを置かないため、後から `initCause` を呼ぶと `IllegalStateException` になる。
  原因の例外のメッセージには SQL と入力値が入るため、例外を記録する経路（API のログ、span の例外のイベント、Listener の失敗のログ）のどこにも渡さない。
  WARN 以上は通知と標準出力のロググループに出るため使わない。
  500 は既存どおり ERROR の `Unhandled API exception` で記録する。
- 例外、HTTP、ログ、非同期の Listener の対応は、[業務上の失敗の例外](../backend/class-roles/business-exception.md)の表に置く。
  Listener では 3 つの型を回復不能として自動で再試行しない。
- `IllegalStateException`、`IllegalArgumentException`、`NoSuchElementException` などの JDK の例外は、4xx に対応づけず 500 のままにする。
  これらはプログラムの誤りにも使われ、まとめて 4xx にすると誤りが 4xx に化けて隠れるためである。
- `TableWriter` は、主キーの行がないときに `NotFoundException` を投げる。
  子の行の更新が 1 行でないことは、ルートの行ロックの下で読んだ自然キーに対する更新で起こらない状態のため、`IllegalStateException` にする（ADR-054）。
  `shared.persistence` は Spring Web に依存しない。
- `23505` は、`TableWriter` の変換の入口で、原因を付けずに `Kind.UNIQUE` の `ConflictException` に変える。
  集約ルートの INSERT の入口 `TableWriter.insert` を足し、同じ変換を通す。
  `DataIntegrityViolationException` の他の違反（外部キー、NOT NULL、CHECK、排他制約）は、入力の検証か実装の誤りであるため 500 のままにする。
- Repository の実装は、Spring の例外（`CannotAcquireLockException`、`DuplicateKeyException`）を catch して変えない。
  書き込みの `55P03` と `23505` は、`TableWriter` が SQLSTATE で、原因を付けずに種類（`Kind.LOCK` か `Kind.UNIQUE`）を付けた `ConflictException` に変える（ADR-054）。
- ArchUnit に 3 つの規則を足す。
  `noSuchElementExceptionIsNotThrown` は、`NoSuchElementException` の生成と引数なしの `Optional.orElseThrow()` を禁じる。
  `httpTypesAreNotUsedByInnerLayers` は、`shared` と各モジュールの `domain`、`application`、`infrastructure.persistence` から Spring Web、Spring の HTTP、Servlet の型への依存を禁じる。
  `httpErrorsAreMappedOnlyInErrorPresentationWeb` は、`error.presentation.web` の外で `ProblemDetail`、`ErrorResponseException`、`ResponseStatusException`、`@ExceptionHandler`、`@ControllerAdvice`、`@RestControllerAdvice`、`@ResponseStatus` に依存することを禁じる。
- 例外と値を、失敗の前に書いた行とイベントの出版を commit してよいかで使い分ける。
  要求を断り、トランザクションで書いたものを取り消す失敗は、3 つの型の例外で投げる。
  業務の状態に記録して正常に終える結果（決済の拒否、契約の不備、請求しない、処理済み）は、Result の値で返す。
  場合が閉じていれば、Result を sealed interface と入れ子の record で表し、`@Nullable` の component で場合を表さない。
  値を返すと Spring は commit し、Spring Modulith は出版を `COMPLETED` にする。
  値の失敗を `setRollbackOnly()` で rollback させない。
  ArchUnit は、`*Result` を record か、許可された型がすべて record の sealed interface に限り、`*Result` の record の component に `@Nullable` を付けることを禁じる。
- `shared.failure` を、`dependenciesPointInward` と `sharedModuleIsUsedOnlyByPersistenceAdapters` の除外に足す。
  これは #130 が `shared.concurrency` のために足した 2 か所と同じ扱いである。
  Domain Service が 422 を投げるため、`domainServicesDependOnlyOnDomainAndJava` の許す型にも足す。

## Consequences

### Positive

- 機能モジュールは型を投げるだけで、404、409、422 のステータス、本文、ログが決まる。
- 画面は 422 と 409 の理由を `type` で区別でき、理由を示す `title` を見出しに出せる。
- 理由のない 422 は、コンストラクタがないためコンパイルが通らない。
  Problem Details を機能モジュールで作る書き方は、ArchUnit が止める。
- Controller に try と catch が要らない。
- `shared.persistence` から Spring Web への依存が消え、`shared` が HTTP に依存しないことを ArchUnit で検査する。
- 一意制約の違反が、500 ではなく 409 になる。
- 見つからないことを `NoSuchElementException` で表す誤りを、ArchUnit が検出する。
- 409 のログに SQL と入力値が出ない。
  原因の連鎖を発生源で作らないため、Collector や logback で文字列を消す設定が要らない。
- 一意制約の違反、ロック待ちの失敗、版の不一致の 409 を、ログの `conflict.kind` で区別できる。

### Negative

- `shared.failure` の 2 つの型が public になり、アーキテクチャ指標の全体の相対可視性の上限を、外部可視型数 20、全型数 33 の実測値に書き直した。
- `ConflictException` の意味を一意制約の違反へ広げ、楽観的ロックの語彙を置く `shared.concurrency` に置いたままにする。
  パッケージの名前は、3 つの型の一部を表さない。
- `TableWriter` の 409 のメッセージは、テーブル名とルートの一意の列の値を持ち、制約名を持たない。
  制約名を取るには PostgreSQL の JDBC ドライバへのコンパイル時の依存が要る。
- 原因がないため、PostgreSQL のエラーの詳細（`detail`、`hint`）をログで見られない。
- `ConflictException.Kind` で public の型が 1 つ増え、アーキテクチャ指標の全体の相対可視性の上限を書き直した。
- `ConflictTranslator` は catch した例外を原因に渡さない。`translate` が例外を受けて `ConflictException` を作るので、PMD の `PreserveStackTrace` と Error Prone の `UnusedException` は抑止せずに通る。原因を持たないことは `TableWriterTest` の `hasNoCause()` が守る。
- `TableWriter` を通さない INSERT の `23505` は 500 のまま残る。
  H1 が `TableWriter` の外の `insertInto` などを禁じる（ADR-054）。
- `Optional.get()`、`Iterator.next()`、`Optional::orElseThrow` のメソッド参照などが中で投げる `NoSuchElementException` は検出しない。
- 404 は `about:blank` のままなので、画面は 404 の理由を本文から区別できない。
- 理由の定数を足すたびに、日本語と英語の `title` を足す。
  足し忘れは `ProblemTitleMessagesTest` が落とす。

### Neutral

- Listener の try と catch、ステータス管理テーブルは [issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108) で扱い、自動の再投入は [ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) で定める。
- [ADR-060](ADR-060-generate-uuid-primary-keys-in-application.md) の不正な UUID の 400 は、この ADR の範囲外とする。
- [ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) の `shared` の範囲に `shared.failure` を足した。
- 422 と 409 の `type` の形と、OpenAPI への載せ方は [ADR-125](ADR-125-derive-problem-types-from-failure-reasons.md) で決める。

## Alternatives Considered

### 選択肢1: error モジュールのルートの公開契約に置く

- **Description**：`com.example.demo.error` の直下に 2 つの例外を置く。
- **Pros**：例外と HTTP の対応が 1 つのモジュールにそろう。
- **Cons**：`moduleRootTypesAreRecordsEnumsOrQueries` はモジュールルートに record、enum、`<Feature>Queries` しか置かせない。
  `dependenciesPointInward` はモジュールルートを Application の層に数えるため、`domain.model` からの依存が Domain から Application への依存になる。
  規則に例外を足して通しても、全機能の Domain が HTTP の境界を担う `error` に依存し、機能モジュールの間の連携に例外の型という 3 つ目の経路が加わる。

### 選択肢2: shared.concurrency に足す

- **Description**：`NotFoundException` と `BusinessRuleViolationException` を `shared.concurrency` に置く。
- **Pros**：ArchUnit の規則の変更がいらない。
- **Cons**：ADR-054 が「楽観的ロックの語彙」と定めたパッケージに、ロックと関係のない型を置くことになり、名前が内容を表さない。

### 選択肢3: ConflictException も shared.failure へ移す

- **Description**：3 つの型を `shared.failure` にそろえる。
- **Pros**：3 つの型が 1 か所にそろう。
- **Cons**：`ConflictException` は `TableWriter`、`ApiExceptionHandler`、テスト、フィクスチャ、十数の文書が参照している。
  移しても振る舞いは変わらず、差分だけが増える。

### 選択肢4: JDK の例外を 404 と 422 に対応づける

- **Description**：`NoSuchElementException` を 404、`IllegalStateException` を 422 にする。
- **Pros**：型を足さない。
- **Cons**：`IllegalStateException` はプログラムの誤り（`TableWriter` の 2 件以上、`PgmCdAspect` の未束縛）にも使い、誤りが 422 に化けて隠れる（ADR-054 の選択肢 10 と同じ理由）。
  `NoSuchElementException` は `Iterator.next()` と `Optional.get()` も投げるため、プログラムの誤りが 404 に化ける。

### 選択肢5: ErrorResponseException の継承か @ResponseStatus

- **Description**：例外に Spring の `ErrorResponseException` を継承させるか、`@ResponseStatus` を付ける。
- **Pros**：handler を足さない。
- **Cons**：Domain が Spring と HTTP の型に依存し、`domainModelDoesNotDependOnFrameworks` に反する。
  `@ResponseStatus` は `@ExceptionHandler(Exception.class)` が先に受けるため効かない。

### 選択肢6: ExecuteListener で 23505 を全体で変える

- **Description**：jOOQ の `ExecuteListener` で、すべての文の `23505` を `ConflictException` に変える。
- **Pros**：INSERT の入口を足さず、すべての文で変わる。
- **Cons**：Spring Boot の `ExceptionTranslatorExecuteListener` を差し替えるか、その順序に依存する。
  ADR-054 が変換を `TableWriter` に集めた方針から外れ、`@JooqTest` のスライスに Bean を足す必要がある。

### 選択肢7: 原因を付けたまま、Collector か logback で Caused by を消す

- **Description**：`ConflictException` は原因を持ったままにし、Collector の transform か logback の設定で、409 の `exception.stacktrace` から `Caused by` 以降を消す。
- **Pros**：アプリのコードを変えず、PostgreSQL のエラーの詳細を例外に残せる。
- **Cons**：文字列のフィルタが Collector とアプリの設定に散り、どこで何を消しているかを追いにくい。
  規約は、禁止された値をアプリから渡さないと定めている。
  API のログ以外で例外を記録する経路（span の例外のイベント、Listener の失敗のログ）には SQL が残る。

### 選択肢8: 原因を連鎖に残し、ログでだけ例外を付けない

- **Description**：`TableWriter` は原因を付けたままにし、`ApiExceptionHandler` の 409 で `setCause` を呼ばない。
- **Pros**：例外の型を変えない。
- **Cons**：`exception.type`、`exception.message`、`exception.stacktrace` がすべて消え、どのテーブルの競合かも分からなくなる。
  SLF4J には連鎖の一部だけを落とす API がなく、残すには例外を作り直すか整形することになり、規約の「例外の詳細を独自に整形しない」に反する。
  ログ以外で例外を記録する経路には SQL が残る。

### 選択肢9: 検査例外を業務上の失敗にする

- **Description**：呼び出し側に処理を強いるため、業務上の失敗を検査例外にする。
- **Pros**：呼び出し側が失敗の処理を書き忘れない。
- **Cons**：Spring は既定で検査例外を rollback せず、`@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)` か各 `@Transactional` の `rollbackFor` が要る。
  ラムダと `Stream` の中で投げられず、`Optional.orElseThrow` の形も崩れる。
  呼び出し側が失敗を処理せず `ApiExceptionHandler` が受けるため、強いる相手がいない。

### 選択肢10: 自作の Result で業務上の失敗を返し、domain で throw しない

- **Description**：失敗を自作の `Result` で返し、`domain` で `throw` しない。
- **Pros**：失敗が型に出て、呼び出し側が見分けて処理を変えられる。
- **Cons**：Java の標準ライブラリに `Result` はなく、Spring もそれを扱わない。
  値を返すと Spring は commit し、イベント出版は完了になるため、rollback と出版の取り消しを手で書くことになる。
  トランザクションの rollback、出版の `FAILED`、`ApiExceptionHandler` の変換は、すべて例外を合図にする。
  業務の状態に記録して正常に終える結果だけは値で返し、sealed interface と record で表す。

### 選択肢11: ErrorResponse を例外に実装させて type を変える

- **Description**：例外に Spring の `ErrorResponse` を実装させ、理由ごとに `type` と `title` を変える。
- **Pros**：理由ごとの `type` を例外の型から決められる。
- **Cons**：`ErrorResponse` は `problemDetail.type.<クラスの完全名>` と `problemDetail.title.<クラスの完全名>` でクラスごとに `type` と `title` を解決する。
  理由の数だけクラスを作ることになり、集約ごとの型を作らない決定とぶつかる。
  `shared.failure` が Spring Web に依存し、選択肢5と同じ理由で `sharedModuleDoesNotDependOnHttp` に反する。
  `type` と `title` は `ApiProblemDetails` が同じ `MessageSource` で作る。

### 選択肢12: 422 と 409 も about:blank のままにする

- **Description**：前の版のとおり、3 つの型をすべて `about:blank` にし、`title` を status ごとの文言にする。
- **Pros**：理由の enum と `title` のキーを足さずに済む。
- **Cons**：画面は 422 と 409 の理由を本文から区別できず、`Order` の 4 つの理由に操作ごとの 1 つの文しか出せない。
  理由ごとの文言を画面で出すには、画面が `detail` か例外のメッセージを読むことになり、内部の識別子が利用者に漏れる。

### 選択肢13: メッセージだけのコンストラクタを残す

- **Description**：`BusinessRuleViolationException` に理由を受け取るコンストラクタを足し、メッセージだけのコンストラクタも残して `about:blank` の 422 にする。
- **Pros**：既存の呼び出しを変えずに済む。
- **Cons**：理由を渡し忘れた 422 が `about:blank` に戻り、画面が理由を区別できない失敗がコンパイルを通る。
  移行する呼び出しは `Order`、`Tenant`、`CurrentTenantController` の数か所だけである。

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-016: API と SPA のメッセージをローカライズする](ADR-016-localize-api-and-spa-messages.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの INSERT と UPDATE を集約の宣言から TableWriter が書く](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)
- [ADR-058: problem type にパスを全部書いた相対 URI を使う](ADR-058-use-path-absolute-relative-uri-for-problem-types.md)
- [ADR-060: UUID の採番をアプリケーションで行い、DB の DEFAULT で採番しない](ADR-060-generate-uuid-primary-keys-in-application.md)
- [ADR-125: 失敗の理由の enum から problem type と title のキーを作る](ADR-125-derive-problem-types-from-failure-reasons.md)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [クラスの役割：業務上の失敗の例外](../backend/class-roles/business-exception.md)
- [HTTPステータスコードの選択](../web-api/status-codes.md)
- [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)
- [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107)
- [issue #145](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/145)
- [issue #541](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/541)
- [可観測性の規約](../observability/conventions.md)
