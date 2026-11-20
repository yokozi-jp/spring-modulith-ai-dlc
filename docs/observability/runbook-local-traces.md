---
type: Runbook
title: ローカルでトレースを見る
description: ローカルの Grafana（grafana/otel-lgtm）で、バックエンドのログの trace ID からトレースを開く手順と、ブラウザを使わずに Grafana の proxy 経由で Tempo を検索する手順。API のトレースをローカルで確かめるときに使う。
tags: [runbook, observability, tracing, grafana, tempo, local]
---

# ローカルでトレースを見る

ローカルのトレースは、Compose の `otel-lgtm`（Grafana、Tempo、Loki、Prometheus が入った `grafana/otel-lgtm`）に溜まる。
バックエンドは OTLP で Collector へ送り、Collector が `otel-lgtm` へ転送する（[可観測性データの規約](conventions.md)）。
この文書は、ローカルでトレースを開く手順を定める。
本番の送り先は対象にしない（[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。

## Grafana を開く

依存サービスとバックエンドを起動してから、[開発のワークフロー](../tooling/dev-workflow.md)の Grafana の URL を開く。
ローカルの Grafana はログインなしで開き、匿名の利用者が Admin になる。

## trace ID からトレースを開く

Problem Details に `traceId` は含まれない。
trace ID は、request の種類に応じて次のように探す。

- **失敗した request**：バックエンドは失敗をログに残し、ログに trace ID を付ける。コンソールの ECS JSON では `traceId`、Grafana の Loki では `trace_id` である。Explore の Loki で `{service_name="demo-api"} | trace_id != ""` を検索し、ログ行の Trace のリンクを押すと Tempo でトレースが開く。
- **成功した request**：ログに残らない。次の「ブラウザを使わずに検索する」か、Explore の Tempo で TraceQL `{ resource.service.name = "demo-api" }` を実行し、一覧から開く。

trace ID が分かっているときは、次の順に開く。

1. Grafana の左メニューから Explore を開き、データソースに Tempo を選ぶ。
2. クエリの種類を TraceQL にして trace ID を貼り、Run query を押す。
3. トレースの span が一覧に出る。

Tempo は span を受け取ってから検索できるまでに時間がかかる（ローカルの実測で 15 秒以内）。
request の直後に見つからないときは、少し待って同じクエリをもう一度実行する。

## ブラウザを使わずに検索する

Compose は Tempo の 3200 を公開しない。
Grafana のデータソース proxy（`/api/datasources/proxy/uid/tempo/...`）に request すると、Grafana が Tempo へ中継する。
データソースの uid は `grafana/otel-lgtm` の `grafana-datasources.yaml` が `tempo` に決めている。
Grafana が匿名で Admin になるため、認証は要らない。

```bash
# リポジトリのルートで実行する。.env を読む範囲を関数の中（サブシェル）に閉じ、秘密の値を手元のシェルへ出さない。
tempo() (
  set -a; . ./.env; set +a
  curl -sS --fail-with-body "http://localhost:${GRAFANA_HOST_PORT}/api/datasources/proxy/uid/tempo/$1" "${@:2}"
)

# service.name が demo-api のトレースを TraceQL で検索する
tempo api/search -G --data-urlencode 'q={ resource.service.name = "demo-api" }' --data-urlencode limit=5

# trace ID を指定して 1 本のトレースの span を取る
tempo api/traces/<traceID>
```

`demo-api` は `.env.example` の `OTEL_SERVICE_NAME` の値である。
値を変えているときは、その値に合わせる。
