---
type: ADR
title: 'ADR-117: Problem Details に traceId を付け、instance を設定しない'
description: 利用者が失敗を問い合わせるときにログを探せるよう、span があるすべての Problem Details に trace ID を traceId 拡張で付け、要求のパスが入っていた instance を設定しない決定。X-Request-Id は付けない。ADR-013 のエラー表現の traceId と instance の行を改める。
tags: [adr, backend, api, observability, problem-details]
---

# ADR-117: Problem Details に traceId を付け、instance を設定しない

## Status

Proposed

## Date

2026-11-23

## Context

[ADR-013](ADR-013-standardize-http-api-contracts.md) は、リクエスト追跡が必要な場合は不透明な `traceId` 拡張を返し、`instance` は特定の失敗を識別する URI を用意できる場合だけ設定すると決めた。
[ログメッセージと属性の書き方](../observability/log-messages.md)も、問い合わせとログを結ぶ ID を `traceId` 拡張で返すとしている。
しかし実装は `traceId` を返していなかった。
そのため、利用者が失敗を問い合わせるときに、ログを探す手掛かりを伝えられなかった（[issue #650](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/650)）。

`instance` は、docs と実装の値が食い違っていた。
Spring Framework 7.0 は、handler が返した Problem Details の `instance` が空なら、要求のパスを入れる。
`/error` へ転送された失敗では、元のパスではなく `/error` が入る。
どちらの値も特定の失敗の発生を識別しないので、RFC 9457 の `instance` の意味にも ADR-013 にも合わない。
Spring Framework 7.1 は、同じ理由で `instance` を入れなくなった（[spring-framework#35969](https://github.com/spring-projects/spring-framework/issues/35969)）。

trace ID の正本は Micrometer Tracing の trace ID であり、独自の request ID を重ねない（[ADR-015](ADR-015-structure-and-protect-observability-data.md)）。
コンソールの ECS JSON のログは `traceId` を、OTLP のログは LogRecord の TraceId を持つ。
応答で同じ trace ID を返せば、その値でログとトレースを検索できる。

## Decision

ADR-013 のエラー表現のうち、`traceId` と `instance` の行を次のとおり改める。

- 要求に span があるとき、4xx と 5xx のすべての Problem Details に、W3C Trace Context の trace ID（32 桁の小文字の 16 進数）を `traceId` 拡張で付ける。業務固有の problem type にも付ける。
- 要求に span がないとき（trace ID が無効なとき、たとえばすべて 0 のとき）は、`traceId` を付けない。
- `instance` は設定しない。OpenAPI の `ProblemDetail` からも外す。
- `X-Request-Id` のような独自の request ID を応答に付けない。ADR-015 のとおり、trace ID を正本にする。
- `traceId` は相関のためだけに使い、認可と冪等性の鍵に使わない。クライアントが `traceparent` で値を決められるためである。

`traceId` は、Spring MVC が Problem Details を書く直前に呼ぶ `ErrorResponse.Interceptor` の 1 か所で扱う。
advice が返す Problem Details も、`/error` が返す Problem Details も、この interceptor を通るためである。
`ErrorResponse.Interceptor` は要求を引数に受け取らないので、interceptor は要求を `RequestContextHolder`（スレッドに束縛した要求）から読む。
interceptor を呼ぶのは、Spring MVC の handler の戻り値の処理だけである。
Spring MVC の中では `FrameworkServlet` が要求を束縛し、Spring Security の拒否の handler（認証、認可、CSRF の失敗）が advice を呼ぶときは、Spring Boot の `RequestContextFilter` が束縛している。
`RequestContextFilter` は Spring Security の filter より前に、ERROR を含むすべての dispatch に登録される。
`ProblemDetailsTraceIdContractTest` は、実際のポートへの 401 と CSRF の 403 で、この順を固定する。
MockMvc は要求を自分で束縛するので、この順を確かめられない。
trace ID は、現在の span ではなく、要求の属性に残る HTTP の observation（`ServerHttpObservationFilter` が作る）の span から取る。
コンテナが `/error` へ転送した失敗は、要求の HTTP の observation の外で処理されるためである。
Spring Boot が observation の filter を REQUEST と ASYNC の dispatch だけに登録するので、ERROR の dispatch では HTTP の observation が開かない。
observation の filter が Spring Security の filter より前にあることも、`ProblemDetailsTraceIdContractTest` の実際のポートへの要求が固定する。
要求に observation がなければ、`traceId` を付けない。

契約の版（`OpenApiConfig.CONTRACT_VERSION`）は、MINOR を上げて `0.8.0` にする。
`instance` を外すことは、ADR-013 に反する値を返していた不具合の修正として扱う。
[APIの互換性と廃止](../web-api/versioning.md)は、バグ修正を後方互換を壊す改修として扱わず、バグに依存するクライアントとは個別に調整するとしている。
この扱いは、次の 2 つがどちらも成り立つこの `instance` だけの判断であり、応答の項目を削除するときの一般の規則にしない。

- 返していた値（要求のパスか `/error`）が、ADR-013 と RFC 9457 の `instance` の意味に反していた。
- 項目を読む既知の利用者がいない。利用者は同時配備の SPA だけで、SPA は `instance` を読まない。

ほかの項目の削除は、versioning.md のとおり後方互換を壊す改修として扱う。
`traceId` の追加は互換な追加である。

## Consequences

### Positive

- 利用者が応答の `traceId` を伝えれば、運用者は同じ trace のログとトレースを探せる。
- `instance` に失敗を識別しない値が入らなくなり、RFC 9457 と ADR-013 に合う。Spring Framework 7.1 へ上げても応答は変わらない。

### Negative

- 同じ失敗でも、要求ごとに本文の `traceId` が変わる。本文の完全一致で 2 つの応答を比べられない。
- Spring Boot 4.2（Spring Framework 7.1）へ上げたとき、interceptor の `instance` を消す処理を外した（[ADR-119](ADR-119-raise-spring-boot-4-2-after-spring-modulith-2-2.md)）。
  Spring Framework が空の `instance` に要求のパスを入れ直すと、`ProblemDetailsConfigTest` が失敗して知らせる。
- sampling で標本にならなかった trace の span は Tempo に送られないので、その trace ID で Tempo のトレースは開けない。
- 要求を `RequestContextHolder` から読むので、filter の順が変わって要求が束縛されないと、`traceId` が付かない。Spring Framework は interceptor の例外を無視するので、失敗は応答に出ない。実際のポートの契約テストが、この順の変化を検出する。

### Neutral

- 応答の `traceId` を画面に出すか、どう出すかは [issue #336](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/336) で決める。この ADR は API の契約だけを扱う。
- 失敗でない応答に ID を付ける必要は、まだ決めていない。

## Alternatives Considered

### 選択肢1: 全応答に X-Request-Id を付ける

- **Description**：要求ごとの ID を作り、すべての応答のヘッダに付ける。
- **Pros**：成功応答でも ID を返せる。
- **Cons**：trace ID とは別の ID がログに増え、ADR-015 の「独自の request ID を重ねない」に反する。失敗でない応答に ID が要る要件もまだない。

### 選択肢2: Problem Details を正規化する処理で instance を空にするだけにする

- **Description**：`ApiProblemDetails` の正規化の処理で、`instance` を `null` にする。
- **Pros**：interceptor が要らない。
- **Cons**：Spring Framework 7.0 は handler が返した後で空の `instance` に要求のパスを入れるので、応答の `instance` は消えない。
  `ProblemDetailsConfigTest` が、Spring Framework のこの振る舞いを確かめる。

### 選択肢3: Problem Details を正規化する処理で、現在の span から trace ID を取る

- **Description**：`ApiProblemDetails` に `Tracer` を渡し、`Tracer.currentSpan()` の trace ID を付ける。
- **Pros**：要求の属性を読まずに済む。
- **Cons**：`/error` を処理する時点の現在の span は、要求とは別の trace に属する。
  [PR #688](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/688) の作業で、`/error` の 400 の契約テストに一時的な出力を足し、現在の span の trace ID が `traceparent` の値と違うことを見た。
  advice と `/error` で取り方を分けると、`ApiProblemDetails` が Problem Details の生成と trace ID の取得の 2 つの責務を持つ。

### 選択肢4: ResponseBodyAdvice で traceId を付け、instance を消す

- **Description**：`ResponseBodyAdvice` の `beforeBodyWrite` で、本文が Problem Details のときに `traceId` を付け、`instance` を消す。
- **Pros**：要求を引数で受け取るので、`RequestContextHolder` と filter の順に頼らない。Spring Framework 7.0 が空の `instance` に要求のパスを入れた後で呼ばれるので、`instance` も消せる。
- **Cons**：成功の応答も含むすべての本文の書き出しで呼ばれ、本文の型で Problem Details を見分ける処理が要る。
  `ErrorResponse.Interceptor` は Problem Details を書くときだけ呼ばれる Spring Framework の拡張点で、この用途に合う。
  filter の順に頼る弱さは、実際のポートの契約テストで補えるので、interceptor を選んだ。

### 選択肢5: 契約の版の MAJOR を上げて 1.0.0 にする

- **Description**：応答の項目の削除を、後方互換を壊す変更として扱う。
- **Pros**：項目の削除を一律に MAJOR とする読み方に合う。
- **Cons**：[ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md) と versioning.md は、`1.0.0` を `/api/v1` を導入するときの版にしている。破壊的変更の手順では Conventional Commits の `!` が要り、release-please がアプリの版も 1.0.0 に上げる。

### 選択肢6: instance に失敗ごとの URI を入れる

- **Description**：失敗ごとに `urn:uuid:` などの URI を作り、`instance` に入れる。
- **Pros**：RFC 9457 の `instance` の意味に合う。
- **Cons**：その URI で引ける資源がなく、`traceId` と同じ役割の ID が 2 つになる。

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457.html)（3.1.5 instance）
- [spring-framework#35969](https://github.com/spring-projects/spring-framework/issues/35969)
- [HTTPステータスコードの選択](../web-api/status-codes.md)
- [ローカルでトレースを見る](../observability/runbook-local-traces.md)
