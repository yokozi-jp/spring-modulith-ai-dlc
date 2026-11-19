---
type: Runbook
title: ローカルでトレースを見る
description: ローカルの Grafana（grafana/otel-lgtm）で、Problem Details の traceId やログの trace_id からトレースを開く手順と、ブラウザを使わずに Grafana の proxy 経由で Tempo を検索する手順を定める運用定義。API のトレースを確かめるとき、遅い request の原因をローカルで調べるとき、otel-lgtm の版を上げるときに使う。
tags: [runbook, observability, tracing, grafana, tempo, local]
---

# ローカルでトレースを見る

ローカルのトレースは、Compose の `otel-lgtm`（Grafana、Tempo、Loki、Prometheus が入った `grafana/otel-lgtm`）に溜まる。
バックエンドは OTLP で Collector へ送り、Collector がそのまま `otel-lgtm` へ転送する（ログの属性だけは Collector が allowlist で絞る。[可観測性データの規約](conventions.md)）。
この文書は、ローカルでトレースを開く手順と、版を上げるときの確認を定める。
本番の送り先は対象にしない（[ADR-043](../adr/ADR-043-send-production-telemetry-to-cloudwatch-via-otel-collector.md)）。

## Grafana を開く

`task compose-up` で依存サービスを起動してから、バックエンドを `task dev` などで動かす。
Grafana の URL とログインの値は、`.env` の次の変数で決まる。

- **URL**：`http://localhost:<GRAFANA_HOST_PORT>`。`.env.example` の既定は 3000。ポートは `127.0.0.1` にだけ公開する。
- **ユーザー**：`GRAFANA_ADMIN_USER`
- **パスワード**：`GRAFANA_ADMIN_PASSWORD`

## trace ID からトレースを開く

1. 調べたい request の trace ID を用意する。Problem Details の `traceId` を使う（[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)）。ログから探すときは、コンソールの ECS JSON では `traceId`、Grafana の Loki では `trace_id` が trace ID である。
2. Grafana の左メニューから Explore を開き、データソースに Tempo を選ぶ。
3. クエリの種類を TraceQL にして trace ID を貼り、Run query を押す。
4. トレースの span が一覧に出る。

Tempo は span を受け取ってから検索できるまでに十数秒かかる。
request の直後に見つからないときは、少し待って同じクエリをもう一度実行する。

## ブラウザを使わずに検索する

Compose は Tempo の 3200 を公開しない。
Grafana のデータソース proxy（`/api/datasources/proxy/uid/tempo/...`）に basic 認証で request すると、Grafana が Tempo へ中継する。
データソースの uid は `grafana/otel-lgtm` の `grafana-datasources.yaml` が `tempo` に決めている。

```bash
# .env を読む範囲を関数の中（サブシェル）に閉じ、ほかの秘密の値を手元のシェルへ出さない。
tempo() (
  set -a; . ./.env; set +a
  curl -s -u "${GRAFANA_ADMIN_USER}:${GRAFANA_ADMIN_PASSWORD}" \
    "http://localhost:${GRAFANA_HOST_PORT}/api/datasources/proxy/uid/tempo/$1" "${@:2}"
)

# service.name が demo-api のトレースを TraceQL で検索する
tempo api/search -G --data-urlencode 'q={ resource.service.name = "demo-api" }' --data-urlencode limit=5

# trace ID を指定して 1 本のトレースの span を取る
tempo api/traces/<traceID>
```

`demo-api` は `.env.example` の `OTEL_SERVICE_NAME` の値である。
値を変えているときは、その値に合わせる。
検索で返らないときも、前節のとおり受け取りから十数秒待つ。

## otel-lgtm の版を上げるとき

[`docker/compose.yml`](../../docker/compose.yml) の `otel-lgtm` は、タグと digest で固定している。
Dependabot の `docker-compose` が更新の PR を出す。
patch と minor の PR は、必須チェックが通ると自動でマージされる（[ADR-021](../adr/ADR-021-group-dependabot-minor-and-patch-updates.md)）。
CI は `otel-lgtm` を起動しないため、版の更新を取り込んだ後（major の PR はマージの前に、そのブランチ）で、ローカルで次の順に確かめる。

1. `task compose-up` を実行し、`otel-lgtm` が healthy になることを確かめる。
2. バックエンドで request を 1 つ送り、十数秒待つ。
3. 前節の検索で、`demo-api` のトレースが返ることを確かめる。
4. 返った trace ID を使い、Explore の Tempo で同じトレースが開くことを確かめる。

3 が失敗したときは、新しい版の `grafana-datasources.yaml` で Tempo の uid が `tempo` のままか、Tempo の URL が `http://127.0.0.1:3200` のままかを見る。
uid が変わっているなら、この文書の proxy の URL を直す。
