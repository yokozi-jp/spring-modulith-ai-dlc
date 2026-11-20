# リクエストの Rate、Errors、Duration

バックエンドの HTTP request の件数、4xx と 5xx の件数、処理時間が、Grafana の Prometheus で見られる。

## Sub-features

- Rate：`http_server_requests_milliseconds_count`
- Errors：同じ指標の `status=~"[45].."`
- Duration：`http_server_requests_milliseconds_bucket` の `histogram_quantile`
- 同梱ダッシュボード：JVM Overview と RED Metrics（classic と native）

## How to get to it (user POV)

Grafana の Dashboards から JVM Overview か RED Metrics を開く。
Explore の Prometheus で上の PromQL を実行してもよい。

## Driving it with check.sh

`check.sh metrics` の「RED」3 行と、`check.sh dashboards` の該当ダッシュボードを見る。
先に `.env` の `SERVER_PORT` のバックエンドへ、`curl -s localhost:<SERVER_PORT>/api/orders` を数十回送る（401 が返り、指標が出る）。

## Gotchas

- RED Metrics の 2 つのダッシュボード（classic と native）は、全パネルが No data である（otel-lgtm 0.40.0 で確認）。
  クエリが OpenTelemetry の名前（`http_server_request_duration_seconds_*` など）を探すが、この backend は Micrometer の名前を出す。
  さらに `instance=~"$instance"` の All が `.+` に展開され、`instance` 属性の無い系列は一致しない。
  名前を揃えるだけでは埋まらない。Issue #563 で扱う。
- JVM Overview は、`instance` 属性が無いために全パネルが No data である。
  クエリには Micrometer の名前の分岐もあり（Rate、Duration、Heap、GC、Error %）、`service.instance.id` を設定すれば出るはずだが、未確認である。
  Error % は 5xx が無いと、設定後も空になる。
- `rate(...[5m])` は、送信を始めて 1〜2 分は空になる。待ってから見る。
- `RED Duration p95` は FAIL である。
  `histogram_quantile` が NaN を返す。Micrometer の OTLP が、histogram の bucket（`+Inf` 以外）を出していない。
  `percentiles-histogram` か SLO を有効にすると出るはずだが、未確認である。Issue #563 で扱う。
- 5xx の指標は、5xx を起こさないと出ない。無いことを失敗と読まない（4xx は出る）。
