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

`task check`は、バックエンドの静的解析の前に`task lint-session-sql`でセッションに状態を残すSQLがないことを確かめる。

## フロントエンドを開発するとき

```bash
task fe-dev
# コードを変更する
task fe-check
```

`frontend/package.json`か`frontend/pnpm-lock.yaml`が変わったとき（ライブラリを追加、更新したときや、それを含む変更をpullしたとき）は、`task fe-dev`の前に`task fe-install`を実行する。

`frontend`でパッケージのコマンドを直接実行するときは、`npx`ではなく`vp`（`vp exec <コマンド>`、`vp test run <ファイル>`）を使う。
`frontend/package.json`の`devEngines`がpnpmを指定しているため、`npx`はnpmとして拒否される。

## git worktreeで作業するとき

worktreeには`node_modules`が無いため、作った直後にルートとフロントエンドの依存を入れる。

```bash
git worktree add -b <ブランチ> ../<ディレクトリ> origin/main
cd ../<ディレクトリ>
npm install        # Lefthookとcommitlint
task fe-install    # フロントエンド
```

依存を入れないと、commitとpushのフックがcommitlintとフロントエンドの検査の依存を見つけられずに失敗する。

## エージェントがcommitとpushをするとき

エージェントは`task agent-commit`と`task agent-push`でcommitとpushをバックグラウンドで始め、`task agent-wait`で終わりを待つ。
`task agent-commit`は、渡したパスの外にstage済みの変更があればstageの前に止まり、渡したパスだけをcommitする。
`git add -A`、`git commit --amend`、`git push --force`は使わない。

```bash
task agent-commit -- <メッセージのファイル> <パス>...
task agent-wait -- <実行のディレクトリ>    # agent-commit が表示したもの。終わるまで繰り返す
task agent-push
task agent-wait -- <実行のディレクトリ>    # agent-push が表示したもの
```

pre-commitの検査とpre-pushの検査は数分かかることがあり、tool callの時間切れや中断ではフックは止まらない。
たとえば`backend/`のJavaかGradleを変えたpushでは、pre-pushが`be-lint`、`be-test`（全テスト）を順番に実行する。
そのため、どちらのTaskもフックの終わりを待たずに、実行のディレクトリ（`/tmp/agent-git-commit.XXXXXX`など）を表示して戻る。

### commitを始める

メッセージは[コミットメッセージの規約](../repository/commit-messages.md)に従ってファイルへ書き、commitするパスと一緒に渡す。
パスは呼んだディレクトリからの相対で解釈し、空白を含むパスもそのまま渡せる。
渡したパスの外にstage済みの変更があれば、その一覧を出して止まる。
自分の変更ならパスに足し、別の作業がstageしたものなら、その作業に確かめてから`git restore --staged -- <パス>`で外す。

止まらなければ、渡したパスだけを`git add`し、その時点のindexの写しから`git commit -F`を始める。
gitはpre-commitの間indexをロックせず、フックの後にindexを読み直す。
写しから作るので、フックの間にほかの作業が`git add`したものはこのcommitに入らず、stage済みのまま残る。
フックが失敗したときは、渡したパスがstage済みのまま残るので、直してから同じパスで`task agent-commit`をやり直す。

### pushを始める

`task agent-push`は、今のブランチを`git push -u origin <ブランチ>`でpushする。
前のpre-push（`lefthook run pre-push`のプロセス）が動いていれば、`pgrep`で見つけたプロセスを出して止まる。
pre-pushの`task test`はcompose-testのプロジェクト名とポートが固定なので、別のworktreeのpre-pushとも重ねられない。
前のフックを止めたときは、`docker compose -f docker/compose-test.yml down --volumes --remove-orphans`でテスト用の依存を片付けてからやり直す。

### 終わりを待つ

`task agent-wait`は、実行のディレクトリの終わりの印（`done`）を1秒ごとに確かめ、1回で最大100秒待つ。
終わっていれば、Lefthookの要約の行（`✓`と`✗`）、`exit=<終了コード>`、ログのパスを出し、失敗ならログの末尾20行も出す。
100秒で終わらなければ「まだ実行中です」と出して終了コード75で戻るので、同じコマンドをもう一度実行する。
Taskは失敗の終了コードを201にまとめるため、元の終了コードは`exit=`の行で読むか、`task -x agent-wait`で確かめる。

どちらのTaskも`NO_COLOR=1`を付けて色の制御文字を外す。
Lefthookの出力は`lefthook.yml`の`output`で絞ってある（[Lintとテストのリファレンス](lint-and-test.md)）。
`LEFTHOOK_OUTPUT=summary,failure`でさらに絞ると、成功の扱いで出る`adr-check`の注意喚起が消えるため、付けない。

本体は`tooling/agent-git/agent-git.sh`である。
`task agent-git-test`が、遅いフックを持つ一時のgitリポジトリで、拒否、成功、失敗、待ちの途中、pushの拒否を確かめる。

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
task fe-install            # 初回はフロントエンドの依存をインストールする
task fe-dev                # SPAを起動し、APIとOIDCを同一オリジンでproxyする
```

`task dev`（`task be-run`）は、業務データ（product、ordering、payment）が空なら、バックエンドの起動の前に開発用の代表データを入れる。
`task be-migrate`の前に`task dev`を実行したときは表がないので入らず、マイグレーションの後の次の起動で入る。
シーダーはテストのソースにあるので、テストのソースがコンパイルできないと`task be-run`と`task dev`は起動しない。
その場合はテストを直すか、`backend`で`./gradlew bootRun`を直接実行する。

`.env.example`に変数が増えたら、`.env`に同じ変数を足す。
既存の`.env`と`.env.test`の値は上書きされない。
Taskのテスト系タスクは、`.env.test`に足りない変数を`.env.test.example`の行のまま追記し、追記した変数名を出す。
たとえば決済代行のClientを足した変更の後は、既存の`.env`へ`.env.example`の`PAYMENT_GATEWAY_BASE_URL`の行を足す。
`task compose-up`などが`OTEL_SERVICE_NAMESPACE is required`のように変数不足で止まったときは、これが原因である。
失敗したイベント出版の定期の再投入を足した変更の後は、既存の`.env`へ`.env.example`の`EVENT_PUBLICATION_RESUBMISSION_ENABLED`、`EVENT_PUBLICATION_RESUBMISSION_WAIT_AGE`、`EVENT_PUBLICATION_RESUBMISSION_IN_FLIGHT_STALENESS`、`EVENT_PUBLICATION_RESUBMISSION_INTERVAL`の4行を足す（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
ローカルでは定期の再投入が短い間隔で有効なので、`FAILED`の出版は消した注文を指すものもリスナーの方針の上限の回数まで自動で再投入され、そのたびにリスナーのERRORが出る。

定期の再投入のロックの表（`010-create-shedlock-table`）を足した変更の後は、既存のローカルのDBに`task be-migrate`を実行する。
表がないと、定期の再投入は毎回`Event publication resubmission failed`のWARNで失敗する。

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
消した注文を指す出版が残ることがあり、手での1回の再投入（`EVENT_PUBLICATION_RESUBMIT_ONCE_TARGET`）で再投入しても注文がないか状態が違うので、決済代行は呼ばれない。
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
task verify
task fe-verify
task lint-duplicates
```

バックエンドだけ、またはフロントエンドだけを変更した場合は、変更していない領域のTaskを省く。
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

PRのCIと同じく差分のクラスだけを変異させるときは、比べる参照を渡す。

```bash
task mutation-test PIT_CHANGED_SINCE=origin/main
```

## docsを変更するとき

```bash
task lint-md
task okf-check
```

`task okf-check`は、ADRの番号の重複、索引と本文の不一致、リンクのラベルと宛先の番号の不一致も検査します。
ADRを追加または番号を変えたときは、`docs/adr/index.md`の行も同じ変更で直します。

## スキルを導入または更新するとき

```bash
task skills-check
```

導入と更新の手順は[スキルの取得元と更新](../agents/skill-sources.md)に従います。

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
