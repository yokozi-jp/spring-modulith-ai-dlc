---
type: Runbook
title: 開発ワークフロー
description: 日常の開発で入口となるTaskを、作業場面ごとの実行順で示す手順書。
tags: [runbook, workflow, task-runner]
---

# 開発ワークフロー

場面に合う節の入口Taskを上から順に実行する。
個々の検査Taskの仕様は[Lintとテストのリファレンス](lint-and-test.md)、DBコマンドの影響範囲は[DB操作コマンドのリファレンス](../database/commands.md)を参照する。

## バックエンドを開発するとき

```bash
task dev
# コードを変更する
task check
```

## フロントエンドを開発するとき

```bash
cd frontend && vp dev
# コードを変更する
cd .. && task fe-check
```

## APIを変更するとき

```bash
# Controller や DTO を変更する
task api-gen
```

契約と生成物の確認、コミット、CIの検査は[APIを変更する](../web-api/runbook-api-change.md)を参照する。

## ローカル環境を初めて起動するとき

```bash
cp .env.example .env       # 環境変数を用意し、パスワードを変更する
task compose-up            # PostgreSQL、Keycloak、Redis、Collector、Grafanaを起動する
task be-migrate            # 初回はマイグレーションを明示実行する
task dev                   # 依存を起動し、バックエンドを起動する
# 別のターミナルで
cd frontend && vp dev      # SPAを起動し、APIとOIDCを同一オリジンでproxyする
```

`task dev`（`task be-run`）は、業務データ（product、ordering、payment）が空なら、バックエンドの起動の前に開発用の代表データを入れる。
`task be-migrate`の前に`task dev`を実行したときは表がないので入らず、マイグレーションの後の次の起動で入る。
シーダーはテストのソースにあるので、テストのソースがコンパイルできないと`task be-run`と`task dev`は起動しない。
その場合はテストを直すか、`backend`で`./gradlew bootRun`を直接実行する。

`.env.example`か`.env.test.example`に変数が増えたら、`.env`と`.env.test`に同じ変数を足す。
既存の`.env`と`.env.test`は上書きされない。
Taskのテスト系タスクは、`.env.test`に足りない変数があると、その変数名を並べて止まる。
たとえば決済代行のClientを足した変更の後は、既存の`.env.test`へ`PAYMENT_GATEWAY_BASE_URL=http://127.0.0.1:8082`を足し、既存の`.env`へ`.env.example`の`PAYMENT_GATEWAY_BASE_URL`の行を足す。
`task compose-up`などが`OTEL_SERVICE_NAMESPACE is required`のように変数不足で止まったときは、これが原因である。
失敗したイベント出版の定期の再投入を足した変更の後は、既存の`.env`へ`.env.example`の`EVENT_PUBLICATION_RESUBMISSION_ENABLED`、`EVENT_PUBLICATION_RESUBMISSION_WAIT_AGE`、`EVENT_PUBLICATION_RESUBMISSION_INTERVAL`の3行を、既存の`.env.test`へ`.env.test.example`の同じ3行を足す（[ADR-075](../adr/ADR-075-resubmit-failed-event-publications-periodically-with-advisory-lock.md)）。
ローカルでは定期の再投入が短い間隔で有効なので、`FAILED`の出版は消した注文を指すものも上限の回数まで自動で再投入され、そのたびにリスナーのERRORが出る。

公開先は次のとおり。

- フロントエンド：<http://localhost:5173>（ブラウザで開く入口）
- バックエンド：<http://localhost:18080>
- Keycloak：<http://localhost:8080>
- Grafana：<http://localhost:3000>

ツールの導入は[開発環境構築ガイド](../local-env-setup/setup.md)を参照する。

## 代表データを入れ直すとき

```bash
task be-seed-reset CONFIRM_RESET=yes   # 業務データを消して代表データを入れ直す
task be-seed                           # 業務データが空のときだけ代表データを入れる
```

reset は業務の4表（`product.m_product`、`ordering.t_order`、`ordering.t_order_line`、`payment.t_payment`）だけを消す。
`modulith`の出版の表とLiquibaseの管理表は残す。
消した注文を指す出版が残ることがあり、`resubmit-once`で再投入しても注文がないか状態が違うので、決済代行は呼ばれない。
出版も消したいときは`task compose-reset CONFIRM_RESET=yes`でDBを作り直す。
接続先がローカルかテストでないとき（`OTEL_DEPLOYMENT_ENVIRONMENT_NAME`が`local`か`test`でない、または`DB_HOST`がloopbackでない）は、DBに触れずに失敗する。

## changesetを追加するとき

作りかけのchangesetを確認する間は次の順で実行する。

```bash
task be-migrate-dev
task be-generate-jooq
task test-dev
```

スキーマタグを確定した後は次の順で実行する。

```bash
task be-refresh-jooq
task be-verify-migrations
task test
```

生成物の管理は[jOOQコード生成物の管理](../database/jooq-codegen.md)を参照する。

## pushする前

変更した領域の入口Taskを実行し、最後に重複検査を実行する。

```bash
task fe-verify
task verify
task lint-duplicates
```

フロントエンドだけ、またはバックエンドだけを変更した場合は、変更していない領域のTaskを省く。
`frontend/src/routes/`を変更した場合は、`task fe-route-tree-check`で`routeTree.gen.ts`の再生成漏れがないことを確かめる。
このTaskは、Collectorのroute allowlistが`routeTree.gen.ts`の`fullPaths`と一致することも確かめる。
OpenAPI契約かOrvalの設定を変更した場合は、`task api-client-check`で`src/api/generated`の再生成漏れがないことを確かめる。
React Doctorは実行に時間がかかるためpre-pushでは実行せず、Frontend CIで`task fe-doctor`を実行する。
ローカルでReact固有の問題を診断するときは、`task fe-doctor`を手動で実行する。

## E2Eテストを実行するとき

開発用のViteとKeycloakを止めてから実行する。

```bash
task e2e
```

失敗した環境を調べるときは`E2E_KEEP_ENV=1 task e2e`で残す。
前提と調べ方は[E2Eテストの方針と書き方](../e2e/testing-strategy.md)を参照する。

## ミューテーションテストを実行するとき

```bash
task mutation-test
```

## docsを変更するとき

```bash
task lint-md
task okf-check
```

## リリース設定を変更するとき

```bash
task release-check
```

## Collectorの設定を変更するとき

```bash
task otel-collector-check
```

このタスクは、バックエンドのログに加えて Faro の fixture もフロントエンドの pipeline（`logs/frontend` と `traces/frontend`）に流して出口を検査する。
例外、View、LCP、INP、CLS の session 相関と、URL token、UUID の session ID、利用者情報、trace の URL 属性が残らないことを確かめる。
最初に、Collector の route allowlist が `routeTree.gen.ts` の `fullPaths` と一致することを確かめる。
ログ属性の allowlist は[可観測性データの規約](../observability/conventions.md)を参照する。

## DASTを実行するとき

```bash
task scan-dast
task scan-dast-active
```

ポート4173、18081、18082、5433、6380、8081を使うので、開発環境とは同時に実行できるが、`task test`とは同時に実行できない。
ZAPとbackendは`--network host`で起動するので、Docker Desktopではhost networkingを有効にしておく必要がある。
レポートは`build/dast/`に出力する（[ADR-056](../adr/ADR-056-run-authenticated-dast-with-zap-in-ci.md)）。

## ローカルサービスを確認するとき

```bash
task compose-ps
task oidc-check
task keycloak-logs
```

## ローカルサービスを停止するとき

```bash
task compose-down
```

全サービスのローカルデータを破棄して初期化するときだけ、確認値を指定する。

```bash
task compose-reset CONFIRM_RESET=yes
```

DBの本番適用と切り戻しは[DBのデプロイと切り戻し](../database/runbook-deploy-and-rollback.md)を参照する。
