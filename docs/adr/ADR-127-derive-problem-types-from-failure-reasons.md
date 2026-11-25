---
type: ADR
title: 'ADR-127: 失敗の理由の enum から problem type と title のキーを作る'
description: problem type を /problems/<パス>、title のキーを problem.title.<パス> にして同じパスから作り、パスをモジュールの 422 では /problems/<module>/<kebab-case> の <module>/<kebab-case>、モジュールによらない problem では CommonProblem の定数の kebab-case にし、モジュールの名前を理由の enum のパッケージから取り、OpenAPI の ProblemDetail.type に x-extensible-enum ですべての type を載せる決定。ADR-058 の業務固有の problem type の形を改める。
tags: [adr, backend, api, error, frontend]
---

# ADR-127: 失敗の理由の enum から problem type と title のキーを作る

## Status

Proposed

## Date

2026-11-24

## Context

[ADR-058](ADR-058-use-path-absolute-relative-uri-for-problem-types.md) は、業務固有の problem type を `/problems/<kebab-case>` にし、`title` を `problem.title.<kebab-case>` で解決すると決めた。
その時点の type は `validation-error`、`csrf-token-invalid`、`tenant-not-selected` の 3 つで、どれも `error` モジュールが名前を持っていた。

[ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md) の今の版は、422 に理由ごとの type を、409 に競合の種類ごとの type を付けると決めた。
422 の理由は、`shared.failure` の interface `FailureReason` を、モジュールごとの enum が実装して表す。
今ある理由は、`ordering` の 4 つと `tenancy` の 3 つである。

理由はモジュールごとに増え、別のモジュールに同じ名前の理由ができうる（どちらのモジュールにも `NOT_FOUND` がある、など）。
ADR-058 の `/problems/<kebab-case>` のままでは、モジュールをまたいで type と `title` のキーがぶつかる。
理由を足すたびに type と `title` のキーを手で書くと、type、`title` のキー、OpenAPI に載せる一覧が食い違いうる。

`FailureReason` を sealed にできないため、実装する enum の一覧はコンパイラから得られない。
名前のないモジュールでは、sealed の型の permits が同じパッケージに限られ、`javac` が止めるためである（[#317](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/317)）。

この版は、Pull Request #737 のレビューを受けて Decision を書き直した。
前の版は、`shared` の `FailureReason` が type と `title` のキーを作り、`error` の `ApiProblemDetails` が同じ接頭辞を別に持っていた。
モジュールによらない type の名前は、`ApiProblemDetails` の定数、競合の種類の `switch`、OpenAPI の一覧、2 つのテストに手で書かれていた。
その後、#540 で ADR-126 の `Idempotency-Key` の 4 つの type を `CommonProblem` に足したので、`CommonProblem` の中身の記述に加えた。

## Decision

ADR-058 の「業務固有の problem type は `/problems/<kebab-case>`」を次のとおり改める。

- 業務固有の problem は、1 つのパスで識別する。
  type は `/problems/<パス>`、`title` のキーは `problem.title.<パス>` で、同じパスから作る。
  2 つを作るのは `error.presentation.web` の `ProblemTypes` だけである。
- モジュールの業務規則の違反（422）のパスは `<module>/<kebab-case>` にする。
  たとえば `ordering.domain.model.OrderFailure.NOT_DRAFT` は、type が `/problems/ordering/not-draft`、`title` のキーが `problem.title.ordering/not-draft` になる。
  - `<module>` は、`FailureReason` を実装する enum のパッケージ `com.example.demo.<module>` の `<module>` の部分で、`FailureReason` の default method `module()` が返す。
    この名前は Spring Modulith がパッケージから決めるモジュールの名前と同じになる。
  - `<kebab-case>` は、enum の定数の名前を小文字にし、`_` を `-` にしたものである。
  - `FailureReason` は HTTP の語彙（type、`title`）を持たない。
- モジュールによらない problem のパスは、`error.presentation.web` の enum `CommonProblem` の定数の名前の kebab-case にする。
  `CommonProblem` は、入力検証、`Idempotency-Key` の誤りと処理中（[ADR-126](ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md)）、CSRF トークン、テナントを選ぶ前の 403 と、409 の競合の種類で、定数ごとに HTTP の status を持つ。
  409 は、`ConflictException.Kind` から網羅した `switch` で選び、版の不一致を `/problems/version-conflict`、行ロックを待ち切れなかった失敗（`55P03`）を `/problems/lock-timeout`、一意制約の違反（`23505`）を `/problems/unique-violation` にする。
  `Kind` を足すとコンパイルが止まる。
- `title` は、日本語と英語の `messages` に置き、コードに既定の文言を書かない。
  実行時にキーがなければ、`title` は status の文言（`problem.title.422` など）になる。
- 理由の型の形は、コンパイラと ArchUnit で決める。
  `BusinessRuleViolationException` は、`FailureReason` を実装する enum の定数だけを受け取る。
  ArchUnit の `failureReasonsAreModuleEnums` は、`FailureReason` を実装する型を、`com.example.demo.<module>` の下の enum に限る。
- 応答が返しうる type の一覧は、`ProblemTypes` が、about:blank、`CommonProblem` の定数、理由の定数から作る。
  理由の定数は、Spring の `ClassPathScanningCandidateComponentProvider` で、ベースパッケージの下の `FailureReason` の enum を実行時に走査して集める。
  定数の本体で抽象メソッドを実装する enum も拾うよう、入れ子でないことだけで候補を選ぶ。
  テストの実行時はテストのクラスも走査するため、テストの enum は `com.example.demo` の外に置く。
- `ProblemTypesTest` は、type の一覧を固定の値と順で確かめ、すべてのパスの `title` のキーが日本語と英語の `messages` にあることを確かめる。
  理由を足す、名前を変える、移す、消すと、このテストを直すことになる。
- OpenAPI の `ProblemDetail.type` に、この一覧を `x-extensible-enum` で載せる。
  `enum` ではなく `x-extensible-enum` にするのは、type の値を足しても、既存のクライアントの型と振る舞いを壊さないためである。
  クライアントは type を応答の文字列のまま比べ（ADR-058）、知らない type は status で扱う。
- API の 404、409、422 のログに、応答の type と同じ値を属性 `problem.type` で付ける。
  409 の `conflict.kind`（`version`、`lock`、`unique`）は ADR-062 のとおり残し、`problem.type` の語彙に合わせない。

## Consequences

### Positive

- 理由を足すときは、enum の定数、日本語と英語の `title`、`ProblemTypesTest` の 1 行を足すだけで、type、`title` のキー、OpenAPI の一覧がそろう。
- type と `title` のキーの形が `ProblemTypes` の 1 か所で決まり、`title` のキーは type から読める。
- モジュールをまたいで理由の名前がぶつからない。
- type のモジュールの部分が Spring Modulith のモジュールの名前と同じなので、type から理由を投げたモジュールを辿れる。
- ログの `problem.type` で、応答と同じ語彙で 422 の理由と 409 の種類を絞り込める。
- oasdiff は、`x-extensible-enum` に値を足す変更を破壊的変更にしない。
  `task api-breaking` と同じ版の oasdiff で、値を 1 つ足した文書を比べて確かめた。

### Negative

- enum を別のモジュールへ移すか、定数の名前を変えると、type が変わる。
  type はクライアントが分岐に使う契約なので、名前の変更は後方互換を壊す変更として扱う（[APIの互換性と廃止](../web-api/versioning.md)）。
  oasdiff は `x-extensible-enum` から値を消す変更を検出しないため、`ProblemTypesTest` の固定の一覧と、その差分のレビューで止める。
- `title` のキーに `/` が入り、`messages` のほかのキーの `.` の区切りと形が違う。
- OpenAPI の文書を作るたびに、ベースパッケージのクラスファイルを走査する。
- テストのソースで `com.example.demo` の下に `FailureReason` の enum を置くと、テストの実行時の一覧と、テストが書き出す OpenAPI に入る。
  `ProblemTypesTest` の固定の一覧が落ちて気づく。
- 409 のログは、同じ種類を `problem.type` と `conflict.kind` の 2 つの属性で持つ。
- `/problems/<module>/` の下も、説明ページのために SPA の route と API を作らずに空けておく。

### Neutral

- 409 は、モジュールの理由を持たず、種類ごとの共通の type にする（[#309](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/309)）。
  どのテーブルの競合かは、ログの例外のメッセージで調べる。
- 404 は `about:blank` のままにする（ADR-062）。
- 409 と 422 の type が `about:blank` から理由ごとの値に変わるのは、契約の版の MINOR を上げる追加として扱う。
  同じリポジトリの SPA は、409 と 422 を type で分岐していない。

## Alternatives Considered

### 選択肢1: モジュールの名前を interface のメソッドで持つ

- **Description**：`FailureReason` に抽象メソッド `module()` を置き、各 enum が `"ordering"` のような文字列を返す。
- **Pros**：パッケージの構成から独立し、enum を別のパッケージへ移しても type が変わらない。
- **Cons**：各 enum に同じ形のメソッドを書き、Spring Modulith のモジュールの名前と食い違っても検出できない。
  食い違いを止めるには、パッケージから取った名前と比べる検査が別に要り、それならパッケージから取れば足りる。

### 選択肢2: ADR-058 の形のまま、定数の名前にモジュールを含める

- **Description**：type を `/problems/<kebab-case>` のままにし、`ORDER_NOT_DRAFT` のように定数の名前で一意にする。
- **Pros**：ADR-058 の形を変えない。
- **Cons**：一意であることを名前の付け方の約束に頼り、ぶつかると type と `title` のキーが重なる。
  定数の名前にモジュールの名前を繰り返すことになる。

### 選択肢3: 定数ごとに type と title のキーを文字列で書く

- **Description**：enum のコンストラクタに type と `title` のキーの文字列を渡す。
- **Pros**：定数の名前を変えても type を保てる。
- **Cons**：定数ごとに 2 つの文字列を手で書き、モジュールの名前の綴りや kebab-case の誤りを、コンパイルも検査も止めない。
  type を保つには、定数の名前を変えなければ足りる。

### 選択肢4: title のキーを problem.title.<module>.<kebab-case> にする

- **Description**：`messages` のほかのキーと同じく `.` で区切り、type のパスの `/` を `.` に替えて `title` のキーにする。
- **Pros**：`messages` のキーの形がそろう。
- **Cons**：type と `title` のキーの区切りが違い、2 つを作る規則が 2 つになる。
  `/` は Java の properties のキーと Spring の `MessageSource` でそのまま使える。

### 選択肢5: CommonProblem に既定の英語の title を持たせる

- **Description**：キーがないときの `title` を、`CommonProblem` の定数ごとに英語の文で持つ。
- **Pros**：`messages` にキーがなくても、英語の `title` が返る。
- **Cons**：英語の `title` が `messages.properties` とコードの 2 か所に書かれ、食い違いうる。
  すべてのキーがあることは `ProblemTypesTest` が確かめるため、キーがない場合は status の文言で足りる。

### 選択肢6: OpenAPI の type を enum にする

- **Description**：`ProblemDetail.type` を `enum` にする。
- **Pros**：生成した client の型が、取りうる値を表す。
- **Cons**：値を足すと、古い client は知らない値を型の外の値として受け取る。
  OpenAPI の `enum` は閉じた集合を表し、理由が増え続ける type に合わない。

### 選択肢7: OpenAPI 3.1 の examples に並べる

- **Description**：`ProblemDetail.type` の `examples` に今の type を並べ、説明に増えうる一覧であることを書く。
- **Pros**：JSON Schema の標準のキーワードで、Zalando の API ガイドラインは 2025 年 10 月から `x-extensible-enum` の代わりにこの形を勧めている。
- **Cons**：`examples` は例を表すキーワードで、値が今返しうる全部であることを、説明文の決まりでしか表せない。
  issue #541 は、type の一覧を `x-extensible-enum` で載せると決めている。

### 選択肢8: OpenAPI の一覧を手で書く

- **Description**：`OpenApiConfig` か `error` モジュールに、type の一覧を文字列で書く。
- **Pros**：実行時の走査が要らない。
- **Cons**：一覧を理由の enum と手で揃えることになる。
  一覧を enum から作るには、`error` モジュールか `OpenApiConfig` が各モジュールの内部の enum に依存し、`error` の `allowedDependencies`（`shared` だけ）に反する。

### 選択肢9: conflict.kind を problem.type の語彙に置き換える

- **Description**：409 のログの `conflict.kind` をやめ、`problem.type` だけで種類を表す。
- **Pros**：409 の種類を表す属性が 1 つになる。
- **Cons**：`conflict.kind` は ADR-062 が決めた属性で、Collector の `keep_keys`、その検査、規約の許可する属性に入っている。
  置き換えると、`conflict.kind` で絞り込む既存の問い合わせが黙って何も返さなくなる。

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-016: API と SPA のメッセージをローカライズする](ADR-016-localize-api-and-spa-messages.md)
- [ADR-058: problem type にパスを全部書いた相対 URI を使う](ADR-058-use-path-absolute-relative-uri-for-problem-types.md)
- [ADR-062: 業務上の失敗を 3 つの例外の型で表し、404、409、422 の Problem Details に対応づける](ADR-062-map-business-exceptions-to-404-409-422.md)
- [ADR-126: GET と HEAD 以外のすべての業務の API に Idempotency-Key を必須にし、モジュールごとの表に Result を保存する](ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
- [Zalando RESTful API Guidelines: Extensible enum（rule 112）](https://opensource.zalando.com/restful-api-guidelines/#112)
- [HTTPステータスコードの選択](../web-api/status-codes.md)
- [クラスの役割：業務上の失敗の例外](../backend/class-roles/business-exception.md)
- [可観測性の規約](../observability/conventions.md)
- [issue #541](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/541)
- `backend/src/main/java/com/example/demo/shared/failure/FailureReason.java`
- `backend/src/main/java/com/example/demo/error/presentation/web/ProblemTypes.java`
- `backend/src/main/java/com/example/demo/error/presentation/web/CommonProblem.java`
