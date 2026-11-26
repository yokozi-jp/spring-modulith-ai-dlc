# リクエストの Rate、Errors、Duration

バックエンドの HTTP request の件数、4xx と 5xx の件数、処理時間が、Grafana の Prometheus で見られる。

## Sub-features

- Rate、Errors、Duration：`check.sh` の `metrics` 配列の「RED」3 行が PromQL を持つ
- 同梱ダッシュボード：JVM Overview と RED Metrics（classic と native）

## How to get to it (user POV)

Grafana の Dashboards から JVM Overview か RED Metrics (classic histogram) を開く。
Explore の Prometheus で `check.sh` の PromQL を実行してもよい。

## Driving it with check.sh

`check.sh metrics` の「RED」3 行と、`check.sh dashboards` の該当ダッシュボードを見る。
`rate(...[5m])` を使うパネルは、直前の 5 分に request が要る。
`.env` の `SERVER_PORT` のバックエンドへ、5 分間 1 秒ごとに次の request を送り、その直後に実行する。

```bash
end=$((SECONDS+300)); while [ $SECONDS -lt $end ]; do
  for p in /api/orders /error /actuator/health/readiness; do curl -s -o /dev/null localhost:<SERVER_PORT>$p; done  # 401、500、200
  sleep 1
done
```

`/error` は認証なしで 500 を返す（`ApiErrorController`）。
Error のパネルと JVM Overview の Error % は、5xx が無いと空になる。

## Gotchas

- 名前と属性は OpenTelemetry の semantic conventions である（[ADR-133](../../../../docs/adr/ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md)）。
  Micrometer の `http_server_requests_milliseconds_*` と `status` は、もう出ない。
- RED Metrics (native histogram) は、`check.sh dashboards` が `SKIP` と表示して走査しない。理由は [ADR-133](../../../../docs/adr/ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md) にある。
- `instance` は `service.instance.id`（JVM の起動ごとの UUID）である。
  再起動すると古い系列が 5 分ほど残り、新しい系列と重なる。
  再起動の直後は、古い系列で OK になることがある。5 分待ってから確かめる。
