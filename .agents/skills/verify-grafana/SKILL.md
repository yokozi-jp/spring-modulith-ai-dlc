---
name: verify-grafana
description: ローカルの Grafana（grafana/otel-lgtm）で、バックエンド demo-api の主要な指標（RED、JVM、Hikari、jOOQ）が Prometheus に届き、同梱のダッシュボードに出るかを、Grafana の proxy 経由で確かめる検証スキル。指標の名前や属性、Micrometer の OTLP 設定、Collector、otel-lgtm の版を変えるとき、ダッシュボードが空のとき、性能テストの前にメトリクスの経路を確かめるときに使う。
---

# ローカルの Grafana の指標の検証

指標の経路は、バックエンド（Micrometer の OTLP レジストリ）、Collector、`otel-lgtm` の Prometheus、Grafana の順である。
このスキルは、Grafana の画面を開かずに、proxy（`/api/datasources/proxy/uid/prometheus/...`）へ PromQL を送って、経路の終点まで届いているかを調べる。
画面の操作は `/verify-demo` の範囲で、トレースの見方は `docs/observability/runbook-local-traces.md` にある。
ヘルパーは `.kiro/skills/verify-grafana/scripts/check.sh` で、読み取りだけを行う。
コマンドはリポジトリのルートで実行する。

## Launch

```bash
task compose-up                    # otel-lgtm、Collector、PostgreSQL、Keycloak など。--wait で healthy まで待つ
task be-migrate                    # 空の DB から使うとき（初回、changeset の追加後）
nohup task be-run > /tmp/be.log 2>&1 &   # 起動した PID を控える
```

準備ができた印は、`check.sh doctor` が OK を返すことである。
`task compose-up` の直後にバックエンドを起動すると、Keycloak の起動前で `clientRegistrationRepository` の生成に失敗する。
`docker ps` で Keycloak が healthy になってから起動する。
Micrometer の指標は 1 分ほど遅れて届くので、起動と request の後に 1〜2 分待つ。

指標を出すために request を送る。
認証なしでも 401 と 403 の指標は出る。
2xx と DB の指標（jOOQ、Hikari の取得）は、`/verify-demo` の journey でログインして注文を作ると出る。

## Doctor

```bash
bash .kiro/skills/verify-grafana/scripts/check.sh doctor
```

Grafana が応答し、proxy 経由で Prometheus に届き、`service_name="demo-api"` の指標があることを調べる。
FAIL なら、先に進まずに原因を直す。

## Drive

```bash
bash .kiro/skills/verify-grafana/scripts/check.sh metrics      # 主要な指標 13 件
bash .kiro/skills/verify-grafana/scripts/check.sh dashboards   # 同梱ダッシュボードの全パネル
bash .kiro/skills/verify-grafana/scripts/check.sh all
```

`metrics` は、RED（Rate、Errors、Duration）、JVM（ヒープ、Metaspace、GC、スレッド、CPU）、Hikari（active、pending、acquire）、jOOQ、Logback の PromQL を実行し、系列が 1 本以上あれば OK とする。
`dashboards` は、`/api/search` で見つけた全ダッシュボードの各パネルの `expr` を実行する。
変数 `$job` は `demo/demo-api`、それ以外は `.*` に置き換える。
指標の名前と属性は Micrometer のもの（`http_server_requests_milliseconds_*`、属性は `status`、`uri`、`method`、`outcome`、JVM は `area` と `id`）で、OpenTelemetry の semantic conventions の名前ではない。

## Evidence

- 実行ごとに `build/verify-grafana/check-<UTC>.txt` へ全出力が残る（`build/` は git の対象外で、`EVIDENCE_DIR` で変えられる）。
- 証拠には、使ったコマンド、バックエンドを起動した worktree と commit、送った request の種類と件数を添える。
- FAIL を OK に変える編集をしない。FAIL は [features/](features/README.md) の Gotchas の既知の失敗と照らし、新しいものだけを報告する。
- 画面の確認（Explore、ダッシュボードの描画）は、このスキルでは確かめていない。確かめたと書かない。

## Cleanup

自分が起動したものだけを止める。

```bash
kill <be-run の PID>               # 名前で kill しない。他の worktree のバックエンドを巻き込む
task compose-down                  # 他のセッションの Compose project を巻き込まないよう、project 名を確かめてから実行する
```

`build/verify-grafana/` は消さない。
