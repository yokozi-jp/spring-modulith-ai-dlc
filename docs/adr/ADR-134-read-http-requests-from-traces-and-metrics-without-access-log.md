---
type: ADR
title: 'ADR-134: HTTP のアクセスログを出さず、要求の記録をトレースと指標で読む'
description: アプリケーションは HTTP のアクセスログを出力せず、要求ごとの記録を受信の span と HTTP の受信の時間の指標で読む。
tags: [adr, observability, logging]
---

# ADR-134: HTTP のアクセスログを出さず、要求の記録をトレースと指標で読む

## Status

Proposed

## Date

2026-11-26

## Context

[処理ごとに記録するログ](../observability/log-output-points.md)は、HTTP アクセスログを要求の受信時と応答の送信時の両方で記録すると定めていた。
`backend/src` には、アクセスログを出す実装がなかった（[#601](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/601)）。

バックエンドは、Micrometer の Observation で HTTP の受信の span と指標を出している。
名前と属性は [ADR-133](ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md) が OpenTelemetry の semantic conventions に揃えた。
受信の span は `http.request.method`、`http.route`、`url.path`、`http.response.status_code` を持ち、`TenantSelectionFilter` が足す `user.id` も持つ（[ADR-110](ADR-110-add-tenant-and-user-ids-to-logs-and-traces.md)）。
件数と処理時間は、`http.server.request.duration` の指標が持つ。

トレースの標本化率は `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` で決まり、既定は 1.0 である。
1 未満にすると、トレースにはすべての要求が残らない。
ブラウザは `traceparent` を付けないので、画面からの要求も標本化の対象になる（[ADR-068](ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。

## Decision

アプリケーションは HTTP のアクセスログを出力しない。
要求ごとの記録は受信の span で、件数と処理時間は `http.server.request.duration` の指標で読む。

すべての要求を残す必要がある運用（監査、攻撃の追跡）は、アプリケーションの前段にあるロードバランサーなどのアクセスログで満たす。

## Consequences

### Positive

- 同じ要求を、ログ、トレース、指標の 3 つに重ねて記録しない。ログの量と費用が増えない。
- 実装、テスト、Collector の allowlist の追加（`http.request.method`、`http.route`、`url.path`、`http.server.request.duration`）が要らない。
- docs が実装と一致する。

### Negative

- 標本化率を 1 未満にすると、トレースからは標本化で落ちた要求が分からない。件数と処理時間だけが指標に残る。
- ログだけで要求を調べる運用は、アプリケーションのログではできない。

### Neutral

- 全要求の記録がアプリケーションの要件になったら、新しい ADR でアクセスログを足す。そのときは必要な属性を Collector の allowlist へ加え、`/actuator/health` 配下を除く。

## Alternatives Considered

### 選択肢1: アクセスログを実装する

- **Description**：`ServerHttpObservationFilter` の観測や filter で、要求時と応答時にログを出す。
- **Pros**：標本化率にかかわらず全要求がログに残る。ログだけで調べられる。
- **Cons**：トレースと指標が持つ情報の重複である。実装と、allowlist への属性の追加が要る。現在、これを必要とする運用がない。

### 選択肢2: Tomcat のアクセスログを使う

- **Description**：サーバーのアクセスログを標準出力へ出す。
- **Pros**：アプリケーションのコードが要らない。
- **Cons**：一行一 JSON の構造化ログ、`trace_id`、Collector の allowlist の規約に合わない別形式のログが増える。

## References

- [#601](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/601)
- [処理ごとに記録するログ](../observability/log-output-points.md)
- [ADR-015](ADR-015-structure-and-protect-observability-data.md)
- [ADR-068](ADR-068-collect-browser-telemetry-with-faro-via-collector.md)
- [ADR-110](ADR-110-add-tenant-and-user-ids-to-logs-and-traces.md)
- [ADR-133](ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md)
