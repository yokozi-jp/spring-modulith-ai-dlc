# リクエストの Rate、Errors、Duration

バックエンドの HTTP request の件数、4xx と 5xx の件数、処理時間が、Grafana の Prometheus で見られる。

## Sub-features

- Rate：`http_server_requests_milliseconds_count`
- Errors：同じ指標の `status=~"[45].."`
- Duration：`http_server_requests_milliseconds_bucket` の `histogram_quantile`
- 同梱ダッシュボード：JVM Overview の Rate、Duration、Error %。RED Metrics（classic と native）

## How to get to it (user POV)

Grafana の Dashboards から JVM Overview か RED Metrics を開く。
Explore の Prometheus で上の PromQL を実行してもよい。

## Driving it with check.sh

`check.sh metrics` の「RED」3 行と、`check.sh dashboards` の該当ダッシュボードを見る。
先に `curl -s localhost:18080/api/orders` を数十回送る（401 が返り、指標が出る）。

## Gotchas

- **RED Metrics の 2 つのダッシュボードは、全パネルが空である**（2026-11 の `otel-lgtm` 0.40.0 で確認）。クエリが OpenTelemetry の名前（`http_server_request_duration_seconds_*` など）を探すが、この backend は Micrometer の名前 `http_server_requests_milliseconds_*` を出す。直すなら、名前を揃える側を決める Issue が要る。
- **JVM Overview の Error % も空である。** クエリが OpenTelemetry の名前と属性 `http_response_status_code` に限られ、Micrometer は `status` を出す。
- `rate(...[5m])` は、送信を始めて 1〜2 分は空になる。待ってから見る。
- 5xx の指標は、5xx を起こさないと出ない。無いことを失敗と読まない（4xx は出る）。
