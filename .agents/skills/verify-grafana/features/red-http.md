# リクエストの Rate、Errors、Duration

バックエンドの HTTP request の件数、4xx と 5xx の件数、処理時間が、Grafana の Prometheus で見られる。

## Sub-features

- Rate：`http_server_request_duration_seconds_count`
- Errors：同じ指標の `http_response_status_code=~"[45].."`
- Duration：`http_server_request_duration_seconds_bucket` の `histogram_quantile`（bucket は 5ms から 10s の 14 個）
- 同梱ダッシュボード：JVM Overview と RED Metrics（classic と native）

## How to get to it (user POV)

Grafana の Dashboards から JVM Overview か RED Metrics (classic histogram) を開く。
Explore の Prometheus で上の PromQL を実行してもよい。

## Driving it with check.sh

`check.sh metrics` の「RED」3 行と、`check.sh dashboards` の該当ダッシュボードを見る。
`.env` の `SERVER_PORT` のバックエンドへ、次の 3 つを 1 秒ごとに送り続け、5 分待ってから実行する。
`rate(...[5m])` を使うパネルは、直前の 5 分に request が要る。

```bash
for p in /api/orders /nope /error; do curl -s -o /dev/null localhost:<SERVER_PORT>$p; done   # 401、404、500
```

`/error` は認証なしで 500 を返す（`ApiErrorController`）。Error のパネルと JVM Overview の Error % は 5xx が無いと空になる。

## Gotchas

- 名前と属性は OpenTelemetry の semantic conventions である（[ADR-133](../../../../docs/adr/ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md)）。
  Micrometer の `http_server_requests_milliseconds_*` と `status` は、もう出ない。
- RED Metrics (native histogram) は空のままである。
  バックエンドは explicit bucket の histogram を送り、このダッシュボードは native histogram を引く。
  `check.sh dashboards` は `GAP` と表示し、失敗にしない。系列が出たら失敗にして、`KNOWN_EMPTY` から外すよう促す。
- `instance` は `service.instance.id`（JVM の起動ごとの UUID）である。再起動すると古い系列が 5 分ほど残り、新しい系列と重なる。
  再起動の直後は、古い系列で OK になることがある。5 分待ってから確かめる。
