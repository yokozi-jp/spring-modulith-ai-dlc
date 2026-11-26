---
type: ADR
title: 'ADR-133: OpenTelemetry の semantic conventions に指標の名前と属性を揃える'
description: Spring Boot の management.observations.conventions で HTTP の受信と JVM の指標を OpenTelemetry の semantic conventions の名前と属性にし、時間の単位を秒、HTTP の受信の時間の bucket を semantic conventions の推奨の境界にし、全シグナルの resource に起動ごとの service.instance.id を付ける判断。
tags: [adr, observability, opentelemetry, metrics, backend]
---

# ADR-133: OpenTelemetry の semantic conventions に指標の名前と属性を揃える

## Status

Proposed

## Date

2026-11-26

## Context

ローカルの `grafana/otel-lgtm`（0.41.0）は、ダッシュボードの JVM Overview (OpenTelemetry)、RED Metrics (classic histogram)、RED Metrics (native histogram) を同梱する。
Issue #563 の時点では、バックエンドの指標を送っても、3 つとも系列が 0 件だった。
ダッシュボードは OpenTelemetry の semantic conventions の名前と属性で Prometheus を引く。
バックエンドの指標とは、次の 4 点が食い違っていた。

1. **名前と属性。** Spring Boot の既定は、HTTP の受信の Timer を Micrometer の `http.server.requests`（tag は `uri`、`method`、`status`）で記録する。
   ダッシュボードは `http_server_request_duration_seconds_*` と `http_response_status_code` を引く。
2. **時間の単位。** Micrometer の OTLP のレジストリは、既定で時間をミリ秒で送る。
   Prometheus の名前は `..._milliseconds_*` になり、ダッシュボードが引く `..._seconds_*` と一致しない。
   semantic conventions の時間の単位は `s` である。
3. **bucket。** Timer に histogram の設定がないと、OTLP のレジストリは `+Inf` の bucket だけを送る。
   `histogram_quantile` は NaN を返す。
4. **`instance` のラベル。** ダッシュボードは変数 `$instance` で `instance=~"$instance"` と絞り、All は `.+` に展開される。
   otel-lgtm の Prometheus は resource の `service.instance.id` から `instance` を作る。
   バックエンドは `service.instance.id` を付けていなかったため、`.+` に一致する系列がなかった。

Spring Boot 4.2 は、`management.observations.conventions: open-telemetry` で Observation の命名を OpenTelemetry に切り替える。
spring-web 7.1 の `OpenTelemetryServerRequestObservationConvention` と、Micrometer 1.18 の JVM の OpenTelemetry の命名が使われる。
この切り替えは同じ Observation から作る span も変える。
HTTP の受信の span の属性は、`http.url` が `url.path`、`uri` が `http.route`、`method` が `http.request.method`、`status` が `http.response.status_code` になる。
`url.path` は Servlet の `getRequestURI` の値で、query string を含まない。
span には、要求のメソッドの生の値（`getMethod` の値）の `http.request.method_original` も新しく付く。
この値はクライアントが決める。
`http.request.method_original` は span の属性だけに付き、指標の tag にはならない。

次の指標は、この切り替えでは名前が変わらない（spring-web 7.1 と Micrometer 1.18 の jar と、ローカルの Prometheus で確かめた）。

- 送信の HTTP の `http.client.requests`（spring-web 7.1 に OpenTelemetry の送信の命名がない）
- GC の `jvm.gc.pause`、HikariCP の `hikaricp.connections.*`、jOOQ の `jooq.query`、アプリの Counter と Gauge

本番の指標は Collector から CloudWatch へ送る（[ADR-043](ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。
CloudWatch は explicit bucket の histogram と指数の histogram の両方を受け、explicit bucket の histogram を取り込むときに指数の histogram へ変換する。
変換では bucket の件数を配り直すため、パーセンタイルの推定の誤差が増えうる。
CloudWatch は、パーセンタイルを使うなら SDK で指数の histogram を使うよう勧めている。

explicit bucket は OpenTelemetry の SDK の既定の集計で、Prometheus の classic histogram として Grafana の `histogram_quantile` で引ける。
Micrometer の `percentiles-histogram` は、既定の範囲（1ms から 30s）で 1 系列あたり 68 個の bucket を作る。

可観測性データの記録の方針は [ADR-015](ADR-015-structure-and-protect-observability-data.md) が決め、span の属性と指標の tag には allowlist をかけない。
テナントと利用者の ID を指標の tag にしないことは [ADR-110](ADR-110-add-tenant-and-user-ids-to-logs-and-traces.md) が決めた。

## Decision

バックエンドの指標と span の名前と属性を、Spring Boot の `management.observations.conventions: open-telemetry` で OpenTelemetry の semantic conventions に揃える。
自前の `ObservationConvention` は作らない。

OTLP で送る時間の単位を、`management.otlp.metrics.export.base-time-unit: seconds` で秒にする。
この設定は、名前の変わらない Timer と `TimeGauge` にも効く。

HTTP の受信の時間（`http.server.request.duration`）に、semantic conventions の推奨の境界を `management.metrics.distribution.slo` で与える。
境界の値は `backend/src/main/resources/application.yaml` にある。
histogram は explicit bucket にし、指数の histogram と `percentiles-histogram` を使わない。

全シグナルの resource に、`management.opentelemetry.resource-attributes` で `service.instance.id` を `${random.uuid}` から付ける。
値は JVM の起動ごとに変わる。
ログ、トレース、OTLP の指標のアダプターは、一度だけ束縛される同じ `OpenTelemetryProperties` の Bean を読むため、`${random.uuid}` は一度だけ解決され、3 つのシグナルは同じ値を持つ。
指標の resource の `service.instance.id` が、ログとトレースの resource と同じ値であることは、`MetricsResourceContractTest` で確かめる。

テナントと利用者の ID（`tenant.id`、`user.id`）は、指標の tag にしない（ADR-110 のまま）。

次の 2 つは直さず、空のまま残す。

- **JVM Overview の Classes のパネル。** Micrometer 1.18 は `jvm.class.count` の単位を `classes` で送る。
  semantic conventions の単位は `{class}` である。
  Prometheus は単位を名前に足して `jvm_class_count_classes` にし、ダッシュボードが引く `jvm_class_count` と一致しない。
  `jvm.thread.count` も単位が `threads` で `jvm_thread_count_threads` になるが、Threads のパネルは Micrometer の `jvm_threads_live` も引くので表示される。
- **RED Metrics (native histogram)。** このダッシュボードは Prometheus の native histogram を引き、OTLP の指数の histogram が要る。
  1 つの指標で classic と native は両立しない。
  explicit bucket を選んだため、空になる。

## Consequences

### Positive

- 同梱の JVM Overview と RED Metrics (classic histogram) のパネルに値が出る（Issue #563 でローカルの otel-lgtm 0.41.0 で確かめた）。
  値が出ないパネルは、`.agents/skills/verify-grafana/scripts/check.sh` の `KNOWN_EMPTY` に理由とともに載る。
- 指標の名前と属性が semantic conventions に揃い、他の OpenTelemetry の計装と同じ名前で引ける。
- 時間の単位が全 Timer で秒に揃う。
  `event.publication.failed.oldest.age` も秒で送られ、[イベント出版の再投入のログイベント](../observability/runbook-event-publication-resubmission.md)の「経過秒数」と一致する。
- 起動したインスタンスごとに系列が分かれ、ダッシュボードの `$instance` で絞れる。

### Negative

- `http.server.requests` の名前と tag で引くダッシュボード、警報、クエリは、`http.server.request.duration` に直す必要がある。
- `base-time-unit: seconds` は、全 Timer の Prometheus の名前を変える（`jvm_gc_pause_*`、`hikaricp_*_acquire_*`、`jooq_query_*` など）。
  `event.publication.failed.oldest.age` の値は、ミリ秒から秒に変わる。
  このリポジトリの外でミリ秒の値で閾値を決めた警報は、閾値が 1000 倍大きすぎる値になる。
- span の属性名が変わる。
  Tempo で `http.url` を引くクエリは `url.path` に直す。
- 起動ごとに `service.instance.id` が変わるため、再起動のたびに新しい系列ができる。
  保存先の系列の数は、起動の回数だけ増える。
- CloudWatch は explicit bucket の histogram を指数の histogram へ変換するため、本番のパーセンタイルは、指数の histogram で送る場合より粗くなりうる。
- Classes のパネルと native histogram のダッシュボードは空のままになる。

### Neutral

- Context に挙げた、名前の変わらない指標は Micrometer の名前のままである。
  Spring か Micrometer が OpenTelemetry の命名を足すと、版を上げたときに名前が変わる。
- 本番でパーセンタイルの精度が要るようになったら、指数の histogram へ移すかを決め直す。
  移すと、ローカルの classic のダッシュボードが空になる。

## Alternatives Considered

### 選択肢1: 指数の histogram で送る

- **Description**：`management.otlp.metrics.export.histogram-flavor: base2-exponential-bucket-histogram` にし、SLO を外す。
- **Pros**：RED Metrics (native histogram) に値が出る（Issue #563 で確かめた）。
  CloudWatch は変換せずに保存し、パーセンタイルの推定が細かい。
  bucket の境界を選ばずに済む。
- **Cons**：classic の `_bucket` の系列が消え、RED Metrics (classic histogram) と JVM Overview の Duration のパネルが空になる。
  SLO とは併用できず、SLO を残すと、その Timer は explicit bucket のまま送られる（Issue #563 で確かめた）。

### 選択肢2: Micrometer の名前のまま、ダッシュボードを直す

- **Description**：既定の `http.server.requests` などを送り、Micrometer の名前で引くダッシュボードを自前で置く。
- **Pros**：既存の名前と tag で引くクエリを直さずに済む。
- **Cons**：otel-lgtm を上げるたびに、同梱のダッシュボードとの差を自前で保つ。
  他の OpenTelemetry の計装と名前が揃わない。

### 選択肢3: 自前の ObservationConvention を作る

- **Description**：HTTP の受信の `ObservationConvention` を自前で書き、semantic conventions の名前と属性を付ける。
- **Pros**：属性の選び方を細かく決められる。
- **Cons**：Spring Boot の切り替え 1 行で同じ結果になる。
  JVM の指標は Observation を通らないので、別の手当てが要る。
  Spring の版を上げるたびに、自前の命名を追う。

### 選択肢4: MeterFilter で jvm.class.count の単位を {class} にする

- **Description**：`MeterFilter` で `jvm.class.count` の `baseUnit` を `{class}` に書き換え、Classes のパネルに値を出す。
- **Pros**：JVM Overview のパネルがすべて埋まる。
- **Cons**：Micrometer の上流の命名の差を、アプリのコードで 1 つずつ埋めることになる。
  `jvm.thread.count` などの同じ差も同じ手当てが要る。
  上流が直したとき、フィルターが残ると二重の手当てになる。

## References

- Issue #563
- [ADR-015](ADR-015-structure-and-protect-observability-data.md)
- [ADR-043](ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)
- [ADR-110](ADR-110-add-tenant-and-user-ids-to-logs-and-traces.md)
- [指標の名前と属性](../observability/metrics.md)
- [OpenTelemetry: Semantic conventions for HTTP metrics](https://opentelemetry.io/docs/specs/semconv/http/http-metrics/)
- [OpenTelemetry: Semantic conventions for JVM metrics](https://opentelemetry.io/docs/specs/semconv/runtime/jvm-metrics/)
- [Amazon CloudWatch: How CloudWatch stores histogram metrics](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/metrics-otel-histograms.html)
- `backend/src/main/resources/application.yaml`
- `backend/src/test/java/com/example/demo/ObservabilityContractTest.java`
- `backend/src/test/java/com/example/demo/HttpServerMetricsContractTest.java`
- `backend/src/test/java/com/example/demo/MetricsResourceContractTest.java`
