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

## ローカルDBを初めて用意するとき

```bash
task compose-up
task be-migrate
```

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
React Doctorは実行に時間がかかるためpre-pushでは実行せず、Frontend CIで`task fe-doctor`を実行する。
ローカルでReact固有の問題を診断するときは、`task fe-doctor`を手動で実行する。

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

ログ属性の allowlist は[可観測性データの規約](../observability/conventions.md)を参照する。

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
