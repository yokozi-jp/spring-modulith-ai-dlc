---
type: ADR
title: 'ADR-140: 送信の HTTP の span の URL からクエリを発生源と Collector で落とす'
description: Spring Boot の RestClient が span と指標の tag に入れる URL のクエリを ClientRequestObservationConvention の Bean で落とし、Bean を通らない client の分を Collector の transform で落とす。計装は Spring Boot の starter と Micrometer の Observation で入れ、security filterchain の span を切る判断。
tags: [adr, observability, tracing, security, backend]
---

# ADR-140: 送信の HTTP の span の URL からクエリを発生源と Collector で落とす

## Status

Proposed

## Date

2026-11-26

## Context

[ADR-015](ADR-015-structure-and-protect-observability-data.md) は、span の属性に allowlist をかけず、Spring Boot が既定で付ける属性に禁止値が入る経路は確認されていないと決めた。
禁止値が入る経路が見つかったときは、その属性を Collector で消すと決めた。

Issue #579 で、その経路が見つかった。
Spring Boot の `RestClient.Builder` から作った client の span は、`http.url` と `uri` にクエリをそのまま入れる。
Spring Framework 7.1.0 の `DefaultClientRequestObservationConvention.requestUri` は `getURI().toASCIIString()` を使う。
`uri` は URI テンプレートから作る。`uri("…?api_key=…")` のように文字列を渡すと、その文字列がテンプレートになり、クエリを含む。
`uri` は `http.client.requests` の tag でもあるため、クエリの値は指標の系列の数も増やす。
[ADR-133](ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md) の `management.observations.conventions: open-telemetry` は受信の命名だけを切り替え、spring-web 7.1 に送信の OpenTelemetry の命名はない。この経路は切り替えの後も残る。

現在の送信は、決済代行（`POST /v1/charges`）、OIDC のトークン取得（POST）、discovery（設定で固定した URL）で、クエリに秘密を載せていない。
今の実害はなく、将来のクライアントを守る判断である。

Boot の `RestClient.Builder` を通らない client（`RestClient.create()`、`WebClient`、JDK の `HttpClient`）は、convention の Bean を通らない。
前例として、Spring Security の既定は `RestClient.builder()` で作るため、Boot の設定が入らなかった（`OidcTokenClientConfig`）。

Spring Security は、1 回の要求ごとに `security filterchain before` と `after` の span を 2 本作り、それぞれがフィルターの数の event を持つ。
どちらも時間を持たず、数だけ増える。

## Decision

計装は `spring-boot-starter-opentelemetry` と Micrometer の Observation で入れる。
OpenTelemetry の Java agent と `opentelemetry-spring-boot-starter` は使わない。
理由は、agent がライブラリの版と合わせる必要があること、別の semantic conventions の span が Micrometer の span と並ぶこと、agent の URL の sanitizer（`otel.instrumentation.sanitization.url.*`）が Micrometer の経路に効かないことである（Issue #320 の調査）。

送信の span の URL からクエリと fragment を、2 か所で落とす。

1. **発生源。** `ClientObservationConfig` が `DefaultClientRequestObservationConvention` を継ぐ Bean を置き、`http.url` と `uri` の値から `?` と `#` 以降を落とす。Boot の `RestClient.Builder` から作る client が使う。
2. **Collector。** `traces` pipeline に `transform/trace_attributes` を置き、`http.url`、`url.full`、`http.target`、`uri` の `?` と `#` 以降を落とし、`url.query` を消す。Bean を通らない client と、将来の計装が付ける属性の受け皿である。

発生源が主で、Collector は保険である。
span の属性に allowlist をかけない判断（ADR-015）は変えない。

`management.observations.enable.spring.security.filterchains: false` で、`security filterchain` の span を切る。認可の span（`authorize request`）は残す。

span の例外の詳細は、ADR-015 の例外の扱い（発生源で禁じず、保存先の保護と表示時のマスクで扱う）を当てる。
Micrometer Tracing の OpenTelemetry bridge は、observation の error で `recordException` を呼び、status の説明に例外のメッセージを入れる。
`RestClient` の 4xx と 5xx の例外のメッセージは応答の本文を含む。
ログの例外の扱いは #321 で決めるので、span はその結論にそろえる。

## Consequences

### Positive

- Boot の `RestClient.Builder` から作る client では、クエリが span にも指標の tag にも入らない。
- Bean を通らない client の URL も、保存先に着く前に Collector で落ちる。
- 数だけ増える span がなくなり、1 回の要求の span 数と event 数が減る。
- 発生源で消す判断が ADR-070 と同じになる。

### Negative

- バックエンドの `traces` pipeline を Collector で加工するようになる。ADR-015 の「traces は加工しない」を前提にした ADR-070 の記述は、この ADR で改まる。
- Collector は属性の名前に依存する。Spring が属性の名前を変えると、気づかず素通りする。`task otel-collector-check` は、この ADR が挙げた 5 つの名前だけを確かめる。
- `security filterchain` の span がなくなり、フィルターごとの経過は Tempo で見えなくなる。

### Neutral

- 例外の詳細の扱いは、#321 の結論を待つ。
- 受信の span の URL は、`management.observations.conventions: open-telemetry` の `url.path` で、クエリを含まない（ADR-133）。

## Alternatives Considered

### 選択肢1: Collector だけで落とす

- **Description**：ADR-015 が決めた、禁止値の属性を Collector で消す方法だけを使う。
- **Pros**：どの client にも効き、アプリのコードを変えない。
- **Cons**：秘密がアプリの外へ出る。属性の名前が変わると素通りする。ADR-070 の発生源で消す判断と食い違う。

### 選択肢2: 発生源だけで落とす

- **Description**：convention の Bean だけを置く。
- **Pros**：秘密がアプリの外へ出ない。単体の試験で確かめられる。
- **Cons**：Boot の `RestClient.Builder` を通らない client に効かない。

### 選択肢3: main のコードが `io.opentelemetry..` に依存してよいのを限る ArchUnit の規則

- **Description**：span と属性を Micrometer の Observation と Tracer だけで作らせ、convention を迂回させない。
- **Pros**：convention の迂回を、コンパイルの前に検知できる。
- **Cons**：`CommonColumns` と `ProblemDetailsConfig` が `io.opentelemetry.api.trace.TraceId` を既に使う。Collector が保険として残るので、得られる安全が小さい。この ADR では入れない。

### 選択肢4: OpenTelemetry の Java agent を使う

- **Description**：agent の計装と URL の sanitizer に任せる。
- **Pros**：計装の範囲が広い。
- **Cons**：Context に挙げた 3 つの理由で、Micrometer の経路と並ぶ。

## References

- Issue #579、#320、#321、#563
- [ADR-015: 可観測性データを構造化し保護する](ADR-015-structure-and-protect-observability-data.md)
- [ADR-070: SQL の span を jOOQ の ExecuteListener で作り、SQL の文と値を入れない](ADR-070-record-sql-spans-with-jooq-execute-listener.md)
- [ADR-133: OpenTelemetry の semantic conventions に指標の名前と属性を揃える](ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md)
- [可観測性データの規約](../observability/conventions.md)
