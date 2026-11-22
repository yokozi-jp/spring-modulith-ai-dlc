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

`task be-lint`のSpotlessは、mainから変えたファイルを全体で検査するため、変えていない既存の行の書式も違反になる。
`task be-format`を実行し、その出力を同じ変更に含める。

## フロントエンドを開発するとき

```bash
task fe-dev
# コードを変更する
task fe-check
```

`frontend/`の下の`package.json`、`frontend/pnpm-workspace.yaml`、`frontend/pnpm-lock.yaml`のどれかが変わったとき（ライブラリを追加、更新したときや、それを含む変更をpullしたとき）は、`task fe-dev`の前に`task fe-install`を実行する。

Dependabotの更新が`frontend/pnpm-lock.yaml`を変えずに来ると、CIの`pnpm install --frozen-lockfile`が`ERR_PNPM_OUTDATED_LOCKFILE`で落ちる。
そのbranchで`task fe-install`を実行し、`frontend/pnpm-lock.yaml`の差分をcommitしてpushする（[ADR-100](../adr/ADR-100-pin-shared-frontend-dependencies-in-pnpm-catalog.md)）。

パッケージのコマンドを直接実行するときは、`npx`ではなく`vp`を使う。
アプリのscript（`dev`、`build`、`test`、`e2e`、`orval`など）は、`frontend`で`vp run --filter @frontend/web <script>`として実行する。
アプリのテストのファイルを1つだけ実行するときと、アプリの依存のコマンドを実行するときは、`frontend/apps/web`で`vp test run <ファイル>`か`vp exec <コマンド>`を実行する。
`frontend/package.json`と`frontend/apps/web/package.json`の`devEngines`がpnpmを指定しているため、`npx`はnpmとして拒否される。

## git worktreeで作業するとき

worktreeを作る前に、mainのworktreeで`npm ci`を実行しておく。
mainのworktreeは最初にcloneした作業ツリーで、`git worktree list`の1行目に出る。
実行しておかないと、新しいworktreeでの`npm ci`は、mainのworktreeにlefthookが無いことを知らせる`prepare`のメッセージで失敗する。

worktreeには`node_modules`が無いため、作った直後にルートとフロントエンドの依存を入れる。

```bash
git worktree add -b <ブランチ> ../<ディレクトリ> origin/main
cd ../<ディレクトリ>
mise trust         # mise.tomlのhttp:kiro-cliがtrustを要する（ADR-090）
mise install --locked  # 未導入の版だけ入れる
npm ci             # Lefthookとcommitlint
task fe-install    # フロントエンド
```

`mise trust`を実行しないと、shimsが`Config files in … are not trusted`で止まり、`task`を呼ぶフックも失敗する。
依存を入れないと、commitとpushのフックがcommitlintとフロントエンドの検査の依存を見つけられずに失敗する。
`npm install`は手元のnpmで`package-lock.json`を書き直し、worktreeに差分を残すことがあるため使わない（npm 11.4.2では`libc`の項目が消えた）。

compose-testを使うTask（`task test`や`task e2e`など、対象は[compose-testのslot](test-slots.md#対象の-task)）は、複数のworktreeで同時に実行してよく、引数もファイルも足さない。
Taskが実行ごとに空いているslotを取り、開始時に次の1行を出す。

```text
slot 1: web http://localhost:21173、backend http://localhost:21080、Keycloak http://127.0.0.1:21081、WireMock http://127.0.0.1:21082（Compose project spring-modulith-test-1）
```

自分のstackには、この行のURLで接続する。
4つのslotがすべて使用中なら、Taskは最大15分待ち、30秒ごとに待っていることとslotを持つworktreeを出すので、止めずに待つ。
出力に自分のworktreeが出ていて、そのworktreeで動いている実行が無ければ、中断した前の実行がslotを持ったまま残っている。
[compose-testのslot](test-slots.md#中断した実行が残した-slot)の手順で片付ける。

## エージェントがcommitとpushをするとき

エージェントはcommitとpushを次のTaskだけで行い、`git commit`と`git push`を直接実行しない。
shellのツールで`git commit`か`git push`を直接実行すると、PreToolUseフック`block-agent-git`が拒否する（[Kiroのフック](../agents/kiro-hooks.md#block-agent-git)）。
フックはtool callの時間切れより長くかかることがあるので、どちらのTaskもフックの終わりを待たずに戻り、`task agent-wait`で待つ。
`task agent-commit`は本物のindexの写しからcommitする（理由は[ADR-091](../adr/ADR-091-commit-agent-changes-from-a-copy-of-the-index.md)）。

```bash
task agent-commit -- <メッセージのファイル> <パス>...
task agent-wait
task agent-push
task agent-wait
```

pre-pushの検査は作業ツリーを対象にする。
`task agent-push`は、追跡しているファイルに未commitの変更があると、push するコミットを検査できないので、変更のファイルを出して止まる。commitするか退避してから実行する。

commitやpushを始められないときは、Taskが理由と直し方を出して止まるので、それに従う（例：worktreeを作った直後で`commitlint`が無いときは、`task agent-commit`が`npm ci`を促す）。
Taskは失敗の終了コードを201にまとめるので、元の終了コードは出力の`exit=`の行かTaskのエラーの`exit status`で読む。
`task agent-wait`が「まだ実行中です」と出したら（Taskのエラーは`exit status 75`）、同じコマンドをもう一度実行する。

Kiro Web のサンドボックスでは、背景のプロセスが tool call の終了で止まる。
`task agent-commit`か`task agent-push`と`task agent-wait`を同じ tool call で実行し、`AGENT_GIT_WAIT_SECONDS`で待つ秒数を tool call の上限より短く延ばす（[Kiro Web の開発環境](../local-env-setup/kiro-web.md)）。

```bash
task agent-push && AGENT_GIT_WAIT_SECONDS=1500 task agent-wait
```

失敗を報告した実行のディレクトリは、ログを読めるように残る。
消し方と、indexをcommitに合わせるコマンドは、`task agent-wait`が出す。

前のpre-pushを止めたときは、止めた`task test`のコンテナがslotを持ったまま残ることがある。
そのままやり直しても次の`task test`は別のslotを取るが、片付けるまでslotを1つ使い続けるので、[compose-testのslot](test-slots.md#中断した実行が残した-slot)の手順で片付ける。

Lefthookの出力は`lefthook.yml`の`output`で絞ってある（[Lintとテストのリファレンス](lint-and-test.md)）。
`LEFTHOOK_OUTPUT=summary,failure`でさらに絞ると、成功の扱いで出る`adr-check`の注意喚起が消えるので、付けない。

## APIを変更するとき

```bash
# Controller や DTO を変更する
task api-gen
```

契約と生成物の確認、コミット、CIの検査は[APIを変更する](../web-api/runbook-api-change.md)を参照する。

## ローカル環境を初めて起動するとき

```bash
cp .env.example .env       # 環境変数を用意し、パスワードを変更する
task compose-up            # PostgreSQL、Keycloak、Collector、Grafanaを起動する
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

マイグレーションロールをsuperuserから分けた変更の後は、既存の`.env`へ`.env.example`の`POSTGRES_SUPERUSER_PASSWORD`の行を足す（[DB接続情報とロール分離](../database/connections.md)）。
この行がないと、`task compose-up`と`task dev`はPostgreSQLのsecretを作れずに止まる。
既存の`postgres_data`ボリュームでは`docker/initdb`が再実行されず、`demo_migration`はsuperuserのまま残るため、`task compose-reset CONFIRM_RESET=yes`でボリュームを消し（ほかのサービスのデータも消える）、`task compose-up`で起動してから`task be-migrate`を実行する。

モジュールごとのDBロール（`docker/initdb/03-create-module-roles.sh`、012 changeset）を足した変更と、`tenancy`のロール（014 changeset）を足した変更の後も、既存の`postgres_data`ボリュームでは`docker/initdb`が再実行されず、モジュールのロールがないまま残る。
`task be-migrate`は012と014のchangesetのGRANTがロールの不在で失敗するため、`task compose-reset CONFIRM_RESET=yes`でボリュームを消し、`task compose-up`で起動してから`task be-migrate`を実行する（[DB接続情報とロール分離](../database/connections.md)）。

セッションの利用者を`AppUserPrincipal`にした変更（[ADR-096](../adr/ADR-096-require-app-user-principal-for-audit-columns.md)）の後は、ブラウザでログアウトしてログインし直す。
ローカルのDBはセッションを残すため、変更の前のセッションのままでは書き込みが500になる（[APIの認証、セッション、権限](../web-api/authentication-and-session.md)）。

テナントと所属の表（016 changeset）を足した変更（[ADR-101](../adr/ADR-101-keep-tenants-and-roles-in-app-database.md)）の後は、既存のローカルの環境で次の順に実行し、ブラウザでログアウトしてログインし直す。

```bash
task keycloak-reimport   # 利用者の ID を固定し、レルムの役割を消した realm.json を読み直す
task be-migrate          # テナントと所属の表を作る
task be-seed             # 2 つのテナントと、test-user と test-admin の所属を入れる
```

`task keycloak-reimport`はKeycloakのデータだけを消して読み直すため、Keycloakのログインのセッションも消える。
読み直さないと、Keycloakのtest-userとtest-adminのIDがseedの所属の利用者と食い違い、ログインしても所属がなく、招待を待つ画面になる。
`task be-seed`は、業務データがあっても、足りないテナントと所属だけを入れる。
test-userは2つのテナント（サンプル商事、サンプル物産）に属するのでログインの後にテナントを選ぶ画面が出て、test-adminはサンプル商事だけに属するので選ばずに業務の画面に入る。

業務テーブルをテナントの列とRLSを持つ形に作り直した変更（018から020のchangeset、[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）の後は、`task be-migrate`を実行し、それが消したローカルの商品、注文、決済の代表データを`task be-seed`で入れ直す。

公開先は次のとおり。

- フロントエンド：<http://localhost:5173>（ブラウザで開く入口）
- バックエンド：<http://localhost:18080>
- Keycloak：<http://localhost:8080>
- Grafana：<http://localhost:3000>（`.env.example`の値。ポートは`.env`の`GRAFANA_HOST_PORT`。トレースの見方は[ローカルでトレースを見る](../observability/runbook-local-traces.md)）

ツールの導入は[開発環境構築ガイド](../local-env-setup/setup.md)を参照する。

## 代表データを入れ直すとき

```bash
task be-seed-reset CONFIRM_RESET=yes   # 業務データを消して代表データを入れ直す
task be-seed                           # 業務データが空のときだけ代表データを入れる
```

代表データ（商品5件、注文6件、決済2件）は、`TenancySeeds`の2つのテナント（サンプル商事、サンプル物産）の両方に入る（[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
業務データが空かの判定とresetの削除は、`tenancy.m_tenant`のテナントごとに、そのテナントを設定して行う。
業務テーブルはRLSでテナントごとに分かれ、テナントを設定しないまま読むとエラーになるからである（[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md#全テナントの行を書き換える処理)）。

reset は業務の4表（`product.m_product`、`ordering.t_order`、`ordering.t_order_line`、`payment.t_payment`）だけを消す。
`tenancy`の表、`modulith`の出版の表、Liquibaseの管理表は残す。
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
`frontend/apps/web/src/routes/`を変更した場合は、`task fe-route-tree-check`で`routeTree.gen.ts`の再生成漏れがないことを確かめる。
このTaskは、Collectorのroute allowlistが`routeTree.gen.ts`の`fullPaths`と一致することも確かめる。
OpenAPI契約かOrvalの設定を変更した場合は、`task api-client-check`で`packages/api/src/generated`の再生成漏れがないことを確かめる。

## E2Eテストを実行するとき

```bash
task e2e
```

開発用のKeycloak（`127.0.0.1:8080`）が動いていれば、slot 0を飛ばしてslot 1以降を使う。
`task e2e-up`がwebかbackendのポートを使用中と報告して止まったときは、そのポートを使うプロセスを止める。

失敗した環境を調べるときは`E2E_KEEP_ENV=1 task e2e`で残す。
`task e2e-up`は起動だけで片付けないので、直接実行したときは、出力のComposeプロジェクトを`-p`に渡し、`docker compose -p spring-modulith-test-<n> -f docker/compose-test.yml --profile e2e down --volumes --remove-orphans`で自分で片付ける。
片付けるまで、そのslotを持ち続ける。
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

## GitHub Actionsのworkflowを変更するとき

```bash
task lint-actions
task lint-actions-security
task ci-gate-check
```

`task ci-gate-check`にはPython 3とPyYAMLが要る（ローカルでは`sudo apt install python3-yaml`）。
`ci.yml`にジョブを足したときは、`CI gate`の`needs`にも足す。
`ci.yml`から呼ばない`pull_request`のworkflowは、`tooling/ci-gate-check/excluded.tsv`に理由を書く。
`zizmor.yml`の`zizmor-action`の`version`は、`mise.toml`のzizmorと同じ版にする。

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

slotを取り、slotのPostgreSQL、Keycloak、WireMockと、slotによらず固定のポート4173、18081、18082を使う。
開発環境、`task test`、`task e2e`とは同時に実行できるが、2つの`task scan-dast`は同時に実行できず、2本目はポートの確認で失敗する。
ZAPとbackendは`--network host`で起動するので、Docker Desktopではhost networkingを有効にしておく必要がある。
backendの起動の前に`task be-migrate`でマイグレーションを適用するため、JDKが要る。
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
