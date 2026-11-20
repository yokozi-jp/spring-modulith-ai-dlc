---
name: verify-grafana
description: ローカルの Grafana（grafana/otel-lgtm）で、バックエンド demo-api の主要な指標（RED、JVM、Hikari、jOOQ）が Prometheus に届き、同梱のダッシュボードに出るかを、Grafana の proxy 経由で確かめる検証スキル。指標の名前や属性、Micrometer の OTLP 設定、Collector、otel-lgtm の版を変えるとき、ダッシュボードが空のとき、性能テストの前にメトリクスの経路を確かめるときに使う。
---

# ローカルの Grafana の指標の検証

指標の経路は、バックエンド（Micrometer の OTLP レジストリ）、Collector、`otel-lgtm` の Prometheus、Grafana の順である。
このスキルは、Grafana の画面を開かずに、proxy（`/api/datasources/proxy/uid/prometheus/...`）へ PromQL を送って、経路の終点まで届いているかを調べる。
画面の操作は `/verify-demo` の範囲ではない。
`/verify-demo` は compose-test の別のスタックを使い、Collector と `otel-lgtm` を持たない。
トレースの見方は `docs/observability/runbook-local-traces.md` にある。
ヘルパーは `.kiro/skills/verify-grafana/scripts/check.sh` で、読み取りだけを行う。
コマンドはリポジトリのルートで実行する。

## Launch

```bash
docker compose -p spring-modulith-local ps   # 先に実行し、既に動いているサービスを控える
task compose-up                              # --wait で healthy になるまで待つ。戻ってから次へ進む
task be-migrate                              # 空の DB から使うとき（初回、changeset の追加後）
setsid nohup task be-run > /tmp/be.log 2>&1 & echo $! > /tmp/be.pid   # PID は新しいセッションのリーダー
```

Compose の project 名は `spring-modulith-local` で固定のため、worktree が違っても同じスタックを共有する。
別の worktree のバックエンドが動いていると、`check.sh` はそのバックエンドの指標を読む。
動かす前に `pgrep -fa bootRun` で確かめ、自分の起動した 1 つだけにする。
Micrometer の指標は 1 分ほど遅れて届くので、起動と request の後に 1〜2 分待つ。

指標を出す request を送る。
認証なしでも 401 と 403 の指標は出る。
2xx と DB の指標（jOOQ、Hikari の取得）を出すには、開発用のスタックで `task fe-dev` を起動してログインし、画面を操作する。

## Doctor

```bash
bash .kiro/skills/verify-grafana/scripts/check.sh doctor
```

Grafana が応答し、proxy 経由で Prometheus に届き、`.env` の `OTEL_SERVICE_NAME` の指標があることを調べる。
FAIL なら `check.sh` は先に進まず、終了コード 1 で止まる。

## Drive

```bash
bash .kiro/skills/verify-grafana/scripts/check.sh metrics      # 主要な指標
bash .kiro/skills/verify-grafana/scripts/check.sh dashboards   # 同梱ダッシュボードの全パネル
bash .kiro/skills/verify-grafana/scripts/check.sh all
```

`metrics` は、`check.sh` の `metrics` 配列の PromQL を実行し、NaN でない値の系列が 1 本以上あれば OK とする。
`dashboards` は、`/api/search` で見つけた全ダッシュボードの Prometheus のパネルの `expr` を実行する。
変数 `$job` は `<OTEL_SERVICE_NAMESPACE>/<OTEL_SERVICE_NAME>`、それ以外は `.+` に置き換える。
`.+` は All の展開と同じである。
`.*` にすると属性の無い系列が通り、画面の No data を見逃す。
指標の名前と属性は Micrometer のものである。
OpenTelemetry の semantic conventions の名前ではない。

## Evidence

- 実行ごとに `build/verify-grafana/check-<UTC>.txt` へ全出力が残る。`build/` は git の対象外で、`EVIDENCE_DIR` で変えられる。
- 証拠には、使ったコマンド、バックエンドを起動した worktree と commit、送った request の種類と件数を添える。
- FAIL を OK に変える編集をしない。
  FAIL は [features/](features/README.md) の Gotchas の既知の失敗と照らし、新しいものだけを報告する。
- `check.sh` は画面を開かない。
  Explore とダッシュボードの描画は、Playwright の Chromium で開いてスクリーンショットを撮り、`build/verify-grafana/screenshots/` に残す。

## Cleanup

自分が起動したものだけを止める。

```bash
kill -- -"$(cat /tmp/be.pid)"      # セッションのリーダー（task be-run）と、その子の Gradle と JVM をまとめて止める。名前で kill しない
```

Launch の最初に控えたサービスが動いていなかったときだけ、`task compose-down` を実行する。
`compose-down` は `--profile backend` も止めるので、別の worktree が使っているスタックでは実行しない。
`build/verify-grafana/` は消さない。
