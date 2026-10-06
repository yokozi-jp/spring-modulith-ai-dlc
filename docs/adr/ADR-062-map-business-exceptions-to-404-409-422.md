---
type: ADR
title: 'ADR-062: 業務上の失敗を 3 つの例外の型で表し、404、409、422 の Problem Details に対応づける'
description: 業務上の失敗を shared.failure の NotFoundException と BusinessRuleViolationException、shared.concurrency の ConflictException の 3 つの型に固定し、ApiExceptionHandler が 404、422、409 の about:blank の Problem Details にして INFO で記録し、23505 を TableWriter で 409 に変え、JDK の例外の誤用を ArchUnit で禁じる決定。
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
- `LockedRoot.updateChild` は、`shared.infrastructure.persistence` から Spring Web の `ResponseStatusException` を投げて 422 にしていた。
- 一意制約の違反（`23505`）は、どこでも 409 に変換していなかった。
- `controller.md` は、見つからない参照を Controller の `ResponseStatusException(HttpStatus.NOT_FOUND)` で 404 にする例を示していた。

機能モジュールはまだ main になく、例外の型を決めても移行の費用は共通処理、テスト、文書に限られる。
API のエラー契約は [ADR-013](ADR-013-standardize-http-api-contracts.md) の RFC 9457 Problem Details、`title` の翻訳は [ADR-016](ADR-016-localize-api-and-spa-messages.md)、ログの扱いは [ADR-015](ADR-015-structure-and-protect-observability-data.md) に従う。

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
- `ApiExceptionHandler` は、3 つの型を `about:blank` の Problem Details にする。
  `title` は既存の `problem.title.404`、`problem.title.409`、`problem.title.422` を `Accept-Language` の言語で返し、`detail` は既存の `ApiProblemDetails.normalize` が消す。
  業務固有の `type` と翻訳した `detail` は使わない。
  型を 3 つに固定するため、型から業務固有の `type` を決められないからである。
- ログは、404 と 422 を INFO の `API business failure`、409 を INFO の `API conflict` で、`http.response.status_code` と例外の `cause` を付けて記録する。
  WARN 以上は通知と標準出力のロググループに出るため使わない。
  500 は既存どおり ERROR の `Unhandled API exception` で記録する。
- 例外、HTTP、ログ、非同期の Listener の対応は、[業務上の失敗の例外](../backend/class-roles/business-exception.md)の表に置く。
  Listener では 3 つの型を回復不能として自動で再試行しない。
- `IllegalStateException`、`IllegalArgumentException`、`NoSuchElementException` などの JDK の例外は、4xx に対応づけず 500 のままにする。
  これらはプログラムの誤りにも使われ、まとめて 4xx にすると誤りが 4xx に化けて隠れるためである。
- `TableWriter` は、主キーの行がないときに `NotFoundException` を投げる。
  `LockedRoot.updateChild` の 0 件は `BusinessRuleViolationException` にし、`shared.infrastructure.persistence` から Spring Web への依存をなくす。
- `23505` は、`TableWriter` の既存の変換の入口 `executeOrConflict` で、`DuplicateKeyException` を原因に付けた `ConflictException` に変える。
  集約ルートの INSERT の入口 `TableWriter.insert` を足し、同じ変換を通す。
  `DataIntegrityViolationException` の他の違反（外部キー、NOT NULL、CHECK、排他制約）は、入力の検証か実装の誤りであるため 500 のままにする。
- Repository の実装は、`updateWhere`、`deleteWhere`、`NOWAIT` が投げる Spring の例外（`CannotAcquireLockException`、`DuplicateKeyException`）を Repository の外へ出さない。
  外へ出すときは、原因に付けた `ConflictException` に変えて投げる。
- ArchUnit に 2 つの規則を足す。
  `noSuchElementExceptionIsNotThrown` は、`NoSuchElementException` の生成と引数なしの `Optional.orElseThrow()` を禁じる。
  `sharedModuleDoesNotDependOnHttp` は、`shared` から Spring Web、Spring の HTTP、Servlet の型への依存を禁じる。
- `shared.failure` を、`dependenciesPointInward` と `sharedModuleIsUsedOnlyByPersistenceAdapters` の除外に足す。
  これは #130 が `shared.concurrency` のために足した 2 か所と同じ扱いである。
  Domain Service が 422 を投げるため、`domainServicesDependOnlyOnDomainAndJava` の許す型にも足す。

## Consequences

### Positive

- 機能モジュールは型を投げるだけで、404、409、422 のステータス、本文、ログが決まる。
- Controller に try と catch が要らない。
- `shared.infrastructure.persistence` から Spring Web への依存が消え、`shared` が HTTP に依存しないことを ArchUnit で検査する。
- 一意制約の違反が、500 ではなく 409 になる。
- 見つからないことを `NoSuchElementException` で表す誤りを、ArchUnit が検出する。

### Negative

- `shared.failure` の 2 つの型が public になり、アーキテクチャ指標の全体の相対可視性の上限を、外部可視型数 20、全型数 33 の実測値に書き直した。
- `ConflictException` の意味を一意制約の違反へ広げ、楽観的ロックの語彙を置く `shared.concurrency` に置いたままにする。
  パッケージの名前は、3 つの型の一部を表さない。
- 一意制約の違反の 409 と楽観的ロックの衝突の 409 は、同じ `API conflict` で記録する。
  区別はログの `exception.type` の原因（`DuplicateKeyException`）で行う。
- `TableWriter.insert` を通さない INSERT と、子の行の `dsl.batch` の INSERT の `23505` は 500 のまま残る。
  ArchUnit では強制しない。
- `Optional.get()`、`Iterator.next()`、`Optional::orElseThrow` のメソッド参照などが中で投げる `NoSuchElementException` は検出しない。
- 業務固有の `type` がないため、画面は 404 と 422 の理由を本文から区別できない。

### Neutral

- Listener の try と catch、ステータス管理テーブル、自動の再投入は [issue #108](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/108) で扱う。
- [ADR-060](ADR-060-generate-uuid-primary-keys-in-application.md) の不正な UUID の 400 は、この ADR の範囲外とする。
- [ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) の `shared` の範囲に `shared.failure` を足した。
- 業務固有の `type` が要るユースケースが出たら、[ADR-058](ADR-058-use-path-absolute-relative-uri-for-problem-types.md) に従って別に決める。

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
- **Cons**：`ConflictException` は `TableWriter`、`LockedRoot`、`DeletedRoot`、`ApiExceptionHandler`、テスト、フィクスチャ、十数の文書が参照している。
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

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-016: API と SPA のメッセージをローカライズする](ADR-016-localize-api-and-spa-messages.md)
- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-054: 楽観的ロックの競合を lock_no の条件と更新件数で判定し、業務テーブルの UPDATE と DELETE を TableWriter に集める](ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)
- [ADR-058: problem type にパスを全部書いた相対 URI を使う](ADR-058-use-path-absolute-relative-uri-for-problem-types.md)
- [ADR-060: UUID の採番をアプリケーションで行い、DB の DEFAULT で採番しない](ADR-060-generate-uuid-primary-keys-in-application.md)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [クラスの役割：業務上の失敗の例外](../backend/class-roles/business-exception.md)
- [HTTPステータスコードの選択](../web-api/status-codes.md)
- [非同期処理の失敗時の再試行と回復](../integration/async-failure-recovery.md)
- [issue #107](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/107)
