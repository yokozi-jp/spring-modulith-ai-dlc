---
type: Runbook
title: ローカルでトレースを見る
description: ローカルの Grafana（grafana/otel-lgtm）で、API の応答の traceId やバックエンドのログの trace ID からログとトレースを開く手順と、ブラウザを使わずに Grafana の proxy 経由で Tempo を検索する手順。API のトレースをローカルで確かめるときに使う。
tags: [runbook, observability, tracing, grafana, tempo, local]
---

# ローカルでトレースを見る

ローカルのトレースは、Compose の `otel-lgtm`（Grafana、Tempo、Loki、Prometheus が入った `grafana/otel-lgtm`）に溜まる。
バックエンドは OTLP で Collector へ送り、Collector が `otel-lgtm` へ転送する（[可観測性データの規約](conventions.md)）。
この文書は、ローカルでトレースを開く手順を定める。
本番の送り先は対象にしない（[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。

## Grafana を開く

依存サービスとバックエンドを起動してから、[開発のワークフロー](../tooling/dev-workflow.md)の Grafana の URL を開く。
ローカルの Grafana はログインなしで開き、匿名の利用者が Admin になる。これは `grafana/otel-lgtm` の既定で、Compose は上書きしない。

## trace ID からトレースを開く

失敗した API の応答の Problem Details は、その要求の trace ID を `traceId` に持つ（[HTTPステータスコードの選択](../web-api/status-codes.md#前提)）。
利用者が問い合わせで伝えた `traceId` も、この節の手順で探す。
Tomcat がアプリへ渡す前に拒否した要求（`text/html` の 400 や 505）は、Problem Details にならず、`traceId` を持たない。
この要求はアプリの filter を通らないので、バックエンドのログにもトレースにも残らない。
そのときは、失敗した要求が[アプリより前に拒否される要求](../web-api/edge-responsibilities.md#アプリより前に拒否される要求)の条件（リクエスト行とヘッダーの合計が 8KB を超える、HTTP の版が不正）に当たるかを確かめる。

trace ID は、ログに出ているかどうかで探し方を分ける。

- **ログに出ている**：trace ID の名前は、コンソールの ECS JSON では `traceId`（[可観測性データの規約](conventions.md)）、Grafana の Loki では `trace_id` である。
  バックエンドは失敗をログに残す。
  Explore の Loki で `{service_name="demo-api"} | trace_id != ""` を検索し、ログ行を開く。
  `traceId` が分かっているときは、`{service_name="demo-api"} | trace_id = "<traceId>"` で、その trace ID を持つログに絞る。
  詳細の Links の `Trace: <trace ID>` を押すと、右の分割ペインで Tempo のトレースが開く。
  見つからないときは、Grafana の右上の期間を、失敗した時刻を含むように広げる。
  その trace ID を持つログには、要求が発行したイベントの非同期リスナーのログも入る（[非同期処理のトレースと監視](../integration/async-observability.md)）。
  SPA の要求は `traceparent` を持たず、Spring Boot が要求ごとに新しい trace を始めるので、要求ごとに別の trace ID を持つ。
  `traceparent` を自分で付けるクライアントが同じ trace ID を使い回すと、複数の要求のログが当たる。
- **ログに出ていない**：ログを出さない失敗もある。
  Spring Security の firewall が標準にない method を拒否した 400 は、ローカルで確かめたとき Loki のログが 0 行で、Tempo にはトレースがあった。
  次の「ブラウザを使わずに検索する」か、Explore の Tempo で TraceQL `{ resource.service.name = "demo-api" }` を実行し、一覧から開く。

trace ID が分かっているときは、次の順に開く。

1. Grafana の左メニューから Explore を開き、データソースに Tempo を選ぶ。
2. クエリの種類を TraceQL にして trace ID を貼り、Run query を押す。トレースの span がタイムラインで表示される。

Tempo は span を受け取ってから検索できるまでに時間がかかる（ローカルの実測で 15 秒以内）。
request の直後に見つからないときは、15 秒待って同じクエリをもう一度実行する。

## ブラウザを使わずに検索する

Compose は Tempo の 3200 を公開しない。
Grafana のデータソース proxy（`/api/datasources/proxy/uid/tempo/...`）に request すると、Grafana が Tempo へ中継する。
データソースの uid は `grafana/otel-lgtm` の `grafana-datasources.yaml` が `tempo` に決めている。
Grafana が匿名で Admin になるため（前節）、認証は要らない。
コマンドはリポジトリのルートで実行する。関数の中（サブシェル）で `.env` を読み、秘密の値を手元のシェルへ出さない。
`api/search` は `start` と `end`（UNIX 秒）を渡さないと、直近 20 分ほどのトレースだけを検索する。
`api/traces/<traceID>` は期間に左右されない。

```bash
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
