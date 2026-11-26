---
type: Convention
title: 指標の名前と属性
description: バックエンドが OTLP で送る指標と HTTP の受信の span の名前と属性、時間の単位、HTTP の受信の時間の bucket、resource の service.instance.id、指標の tag に置かない値と、その検証を定める規約。指標や span の属性を足す、変えるとき、management.observations、management.metrics、management.otlp.metrics、management.opentelemetry.resource-attributes の設定を変えるとき、ダッシュボードや警報のクエリを書くときに読む。
tags: [convention, observability, opentelemetry, metrics]
---

# 指標の名前と属性

バックエンドの指標と span の名前と属性は、Spring Boot の設定で OpenTelemetry の semantic conventions に揃える。
時間は秒で送り、HTTP の受信の時間は semantic conventions の推奨の境界の explicit bucket の histogram で送る。
`tenant.id`、`user.id`、業務の ID、`url.path` は指標の tag にしない。
理由は [ADR-133](../adr/ADR-133-align-metrics-with-opentelemetry-semantic-conventions.md) にある。

## 名前と属性

- `management.observations.conventions: open-telemetry` で命名を切り替え、自前の `ObservationConvention` を作らない。
- 指標の名前の一覧は docs に書かない。
  名前は `backend/src/main/resources/application.yaml` の設定と、`.agents/skills/verify-grafana/scripts/check.sh` の PromQL にある。

## 時間の単位と bucket

- 時間は秒で送る（`management.otlp.metrics.export.base-time-unit: seconds`）。
  この設定は、切り替えで名前の変わらない Timer と `TimeGauge` にも効く。
- `http.server.request.duration` の bucket の境界は、`management.metrics.distribution.slo` で与える。
  境界の値は `application.yaml` にある。
- histogram は explicit bucket にし、`percentiles-histogram` と指数の histogram を使わない。

## resource

- 全シグナルの resource は、`management.opentelemetry.resource-attributes` の `service.instance.id`（`${random.uuid}`）を持つ。
  値は JVM の起動ごとに変わり、ログ、トレース、指標で同じになる。
- Collector は指標を加工しない（[可観測性データの規約](conventions.md#collector-の-allowlist)）。

## 指標の tag に置かない値

指標の tag には、値の種類が限られるものだけを置く。
`tenant.id`、`user.id`、業務の ID、`url.path` を tag にしない。
テナントと利用者の ID を tag にしない理由は [ADR-110](../adr/ADR-110-add-tenant-and-user-ids-to-logs-and-traces.md) にある。
ログとトレースでの 2 つの ID の扱いは、[可観測性データの規約](conventions.md#テナントと利用者)にある。

## 検証

- **`HttpServerMetricsContractTest`**：テナントを選んだ利用者の要求でも、`http.server.request.duration` が `tenant.id` と `user.id` を tag に持たないことを確かめる。
  bucket の境界が semantic conventions の推奨の境界であることと、`management.otlp.metrics.export.base-time-unit` が秒に束縛されることも確かめる。
  OTLP のレジストリはテストのコンテキストにないため、単位は設定の束縛で確かめる。
- **`ObservabilityContractTest`**：ログとトレースの resource が同じ `service.instance.id` を持つことを確かめる。
  指標の resource の `service.instance.id` は検査しない（理由は ADR-133）。
- **`/verify-grafana`**：ローカルの Grafana で、指標が Prometheus に届き、同梱のダッシュボードのパネルに値が出るかを確かめる。
  空のままと分かっているパネルは、`check.sh` の `KNOWN_EMPTY` に理由とともに載る。
  指標の名前、属性、Micrometer の OTLP の設定を変えたら実行する。
