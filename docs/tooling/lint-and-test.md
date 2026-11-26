---
type: Reference
title: Lintとテストのリファレンス
description: 静的解析、スキャン、テストのTask仕様、GitフックおよびCIでの自動実行の対応をまとめ、各検査の内容、成否条件、実行場所を確認するときに読むリファレンス。
tags: [reference, testing, lint, ci]
---

# Lintとテストのリファレンス

検査は[`Taskfile.yml`](../../Taskfile.yml)の公開Taskから実行する。
場面ごとの実行順は[開発ワークフロー](dev-workflow.md)を参照する。
Gitフックの中でDockerを使う検査は、`docker info`が失敗するローカル環境ではスキップし、CIで検査する。
`docker`コマンドがあってもデーモンに接続できなければ、Dockerを使えないとみなす。
判定の本体は`task docker-available`だけにあり、Dockerを使う`lefthook.yml`のジョブが呼ぶ。
compose-testを使うTaskは、実行ごとにslotを取り、slotのComposeプロジェクトとポートで動く（[compose-testのslot](test-slots.md)）。

## 入口Task

- **`task check`**：`lint-session-sql`、`lint-db-clock`とバックエンドの静的解析を実行する。
- **`task verify`**：`lint-session-sql`、`lint-db-clock`、バックエンドの静的解析、マイグレーション検証、テストを実行する。
- **`task test`**：確定済みマイグレーションを使い捨てのPostgreSQLで検証し、バックエンドテスト、OpenAPI契約検査の後に片付ける。
- **`task test-dev`**：`be-test-dev`の別名。
- **`task mutation-test`**：`be-mutation-test`の別名。
- **`task fe-verify`**：フロントエンドの静的解析、未使用コード検査、テスト、テレメトリを無効にした本番ビルドを実行する。
- **`task e2e`**：compose-testでbackendを起動し、Vite previewに対してPlaywrightのE2Eを実行して後片付けする（[E2Eテストの方針と書き方](../e2e/testing-strategy.md)）。
- **`task e2e-up`**：`task e2e`の起動の手順（slotの取得、build、依存の起動、migration、テナントと所属、backend）だけを実行し、片付けない。
  `task e2e`の外で起動して残すときにも使い、その呼び出し側が片付ける。
- **`task e2e-collect-logs LOG=<パス>`**：compose-testのbackendのログを書き、秘密の値、セッションのCookieの値、JWTの形の文字列が入っていれば失敗する。
  `task e2e`が片付けの前に呼ぶ。`task e2e-up`で残したスタックを片付けるときも、その前に呼ぶ。
  単独で実行すると、slotのプロジェクトではなく、Composeの既定のプロジェクト（`spring-modulith-test`）のログを読む。

## バックエンド

- **`task be-lint`**：Spotless、PMD、SpotBugsでmainとtestを検査する。
  Spotlessは`ratchetFrom 'origin/main'`で、mainから変えたファイルを全体で検査する。変えていない既存の行の書式も違反になる。
- **`task lint-session-sql`**：`backend/src/main/java`のJavaと`backend/src/main/resources/db`のSQLとYAMLから、接続を多重化するプロキシ（PgBouncerのトランザクションモード、RDS Proxy）の後ろで壊れる、セッションに状態を残すSQLを検出し、あれば失敗する。
  対象は、[PgBouncerの機能の表](https://www.pgbouncer.org/features.html)でTransaction poolingがNeverの機能のうち、セッションのadvisory lock（`pg_advisory_lock`、`pg_try_advisory_lock`、`pg_advisory_unlock`、`pg_advisory_unlock_all`と`_shared`の版）、`LISTEN`、`WITH HOLD`のカーソル、`PREPARE`と`DEALLOCATE`の文、`ON COMMIT PRESERVE ROWS`と`ON COMMIT DELETE ROWS`の一時表、`LOAD`である。
  大文字と小文字を区別せず、Javadoc、行コメント、YAMLとSQLのコメントの行は除く。
  トランザクションのadvisory lock（`pg_advisory_xact_lock`、`pg_try_advisory_xact_lock`）、`ON COMMIT DROP`、`LISTENER_ID`のような識別子は検出しない。
  検査の本体は`tooling/session-sql-lint/session-sql-lint.sh`で、検出の前に`tooling/session-sql-lint/test.sh`が、検出すべき文と検出しない文のfixtureで終了コードと出力を確かめる。
  `SET`と`RESET`も同じ表でNeverだが、`spring.datasource.hikari.connection-init-sql`がセッションの`SET`を使うため検出しない（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
  設計の理由は[設計の作り込みの程度と段階的な再構築](../principles/right-sizing.md)の「テンプレートの利用先で変わる前提」にある。
- **`task lint-db-clock`**：`backend/src/main/java`のJavaと`backend/src/main/resources/db`のSQLとYAMLから、DBの時計を読む文と、日時から暦日や境界を作る文を検出し、あれば失敗する。
  対象は`CURRENT_DATE`、`CURRENT_TIME`、`CURRENT_TIMESTAMP`、`LOCALTIME`、`LOCALTIMESTAMP`、`now()`、`sysdate`、`clock_timestamp()`、`statement_timestamp()`、`transaction_timestamp()`、`date_trunc(`、`AT TIME ZONE`、`::date`である。
  大文字と小文字を区別せず、Javadoc、行コメント、YAMLとSQLのコメントの行は除く。
  `current_setting('app.current_tenant')`や`current_date_value`のような識別子は検出しない。
  jOOQの`DSL`の呼び出しは[`DateTimeConventionsArchTest`](../backend/architecture-tests.md#日時)が止める。
  検査の本体は`tooling/db-clock-lint/db-clock-lint.sh`で、検出の前に`tooling/db-clock-lint/test.sh`が、fixtureで終了コードと出力を確かめる。
  規約は[日時とタイムゾーンの規約](../datetime/timezone-conventions.md)の「暦日のAPIとSQL」にある。
- **`task be-openapi-lint`**：`OpenApiContractTest`だけを実行してOpenAPI 3.1契約を`openapi/openapi.yaml`へ生成し、Spectralで検査する。
- **`task be-openapi-check`**：直前の`be-test`が書き出した契約をSpectralで検査し、コミット済みの`openapi/openapi.yaml`と差分があれば失敗する。
  テストを実行しないため、`be-test`の後に実行する。
  `be-test`の開始時に作る`backend/build/be-test.stamp`より契約が新しくなければ失敗するので、`be-test`が契約を書き出さなかった実行や、`be-test`なしの実行は通らない。
- **`task be-verify-migrations`**：現在タグが最後のchangelogに宣言されていることと、使い捨てDBでのchangesetの適用、rollback、再適用、現在タグの存在を検証する。
- **`task be-test`**：確定済みchangesetを適用したテスト用のDBで、テストとcoverage検証を実行する。
  JaCoCoの下限は、全体のbranch 88%とline 95%、クラスごとのbranch 65%とline 70%で、下回ると失敗する（[ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)）。
  `-PopenapiExport=true`を渡すので、`OpenApiContractTest`が`openapi/openapi.yaml`を書き出す。
  素の`./gradlew test`は契約に触れない。
  開始時に`backend/build/be-test.stamp`を作り、`be-openapi-check`が契約の書き出しを確かめる印にする。
- **`task be-test-dev`**：作りかけのchangesetを含めて適用したテスト用のDBで、テストを実行する。
  カバレッジは検証しない。
- **`task be-mutation-test`**：PITを実行する。
  `PIT_CHANGED_SINCE`にgitの参照（`origin/main`など）を渡すと、そこからの差分の`src/main/java`のクラスと内部クラスだけを変異させる。
  差分のテスト`FooTest`は、同じパッケージの`Foo`を変異させる対象に加える。
  テストは対象のクラスのパッケージ以下のものだけを使う。
  対象のクラスがないときはPITを実行せず、差分を取れないときは失敗する。
  minionは1つにし、Springの起動を待てるようにtimeoutを既定より広げる。
  PITは既定の`FRECORD`のfilterで、recordのコンパクトコンストラクタと自動生成の`equals`、`hashCode`、`toString`を変異させない。
  コンパクトコンストラクタの変異を確かめるときは、`./gradlew pitest`に`-PpitFeatures=-FRECORD`と`-PpitTargetClasses`、`-PpitTargetTests`を渡し、対象のrecordに絞って実行する。
  既定でfilterを外さないのは、外すとdomain.modelの27クラスだけで変異が85個から193個に増え、増えた分の大半が自動生成のメソッドの覆われない変異になるためである。
- **`task test`**：slotの取得、compose-testのPostgreSQLの起動、`be-verify-migrations`、PostgreSQLの停止、`be-test`、`api-lint`を実行し、最後にslotのプロジェクトを片付ける。
  PostgreSQLの停止はコンテナを消さないので、最後の片付けまでslotを持つ。
- **`task test-deps-leftover-check`**：名前に`spring-modulith-test`を含むコンテナかvolumeが残っていれば失敗する。
  slotのプロジェクト（`spring-modulith-test-<n>`）と、CIが`docker compose`を直接呼ぶときの既定のプロジェクト（`spring-modulith-test`）の両方を検出する。

テストが使うPostgreSQL、WireMockは、テストのJVMごとにTestcontainersが起動して片付け、Gradleのforkを並列にする（[バックエンドのテストの実行基盤](../backend/testing-infrastructure.md)）。
`docker/compose-test.yml`のPostgreSQL（slot 0では5433）は、`be-verify-migrations`のrollbackの検証だけが使う。
`.env.test`は、Gitで管理する`.env.test.example`からコピーして作るローカルファイルで、無ければTaskが作る。
GradleのテストとPITは、接続先以外の設定を`.env.test`から読む。
PITのHTMLとXMLのレポートは、変異対象がある場合に`backend/build/reports/pitest/`へ出力する。
プロパティベーステストとミューテーションテストの採用理由は[ADR-012](../adr/ADR-012-adopt-property-based-and-mutation-testing.md)を参照する。

## フロントエンド

- **`task fe-check`**：workspaceのルートの`frontend`で`vp check`を実行し、Oxfmt、Oxlint、TypeScriptの型を非破壊で検査する。続けて`lint-e2e-seed`を実行する。
  最後に、`e2e/`の`environment.ts`、`fixtures.ts`、`api.ts`、`payment-gateway.ts`と画面のクラス（`order-create-page.ts`、`order-list-page.ts`、`order-detail-page.ts`）をNodeで直接importでき（相対のimportに拡張子`.ts`がある）、Playwrightの外から使えることを確かめる。
- **`task lint-e2e-seed`**：`frontend/apps/web/e2e/`（`.auth/`を除く）に`SEED-`の文字列が大文字小文字を問わずにあれば失敗する。
  ディレクトリが無いときも失敗する。
  E2Eは開発用のシーダーの代表データを読まず、各テストがデータを作る（[ADR-130](../adr/ADR-130-create-e2e-data-per-test-with-product-registration-api.md)）。
  検査の本体は`tooling/e2e-seed-lint/e2e-seed-lint.sh`で、検出の前に`tooling/e2e-seed-lint/test.sh`が、fixtureで終了コードと出力を確かめる。
- **`task fe-knip`**：未参照ファイル、未使用export、未使用依存をKnipで検査する。
  `packages/ui`は`includeEntryExports: true`にし、`exports`で公開した部品の未使用exportも報告する。
- **`task fe-coverage`**：React Compilerを有効にして、`vp run --recursive test`でルートのlint設定のテストと各packageのテストを実行する。
  そのあと、Compilerを外した状態で、ルートを除くpackageごとにVitestのV8 providerのcoverageを測り、下限もpackageごとに検証する。
  `apps/web`の全体の下限はbranch 88%とline 95%、`packages/api`の全体の下限は切り出した時点の実測の少し下のbranch 90%とline 98%、`packages/ui`の全体の下限はbranch 95%とline 95%である。ファイルごとの下限は全packageで共通のbranch 60%とline 65%である。
  `packages/ui`のテストはReact Compilerなしで走る。方針は[フロントエンドのテストと検証](../frontend/testing.md#配置と実行環境)にある。
  全体の下限は各packageの`vite.config.ts`の`thresholds`が、ファイルごとの下限は同じ`thresholds.perFile`が検査する（[ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)）。
  Compilerを外すのは環境変数`FRONTEND_REACT_COMPILER=false`で、理由は[ADR-078](../adr/ADR-078-measure-frontend-coverage-without-react-compiler.md)にある。
  `FE_TEST_ARGS`に渡した値は、ルートのlint設定のテスト、各packageのテスト、coverageの測定のVitestの実行すべてに足す。ファイルのパスは渡さず、reporterなどのoptionだけを渡す（例：`task fe-verify FE_TEST_ARGS=--reporter=dot`）。
  テストのtimeoutは`apps/web/vite.config.ts`の`testTimeout`で30秒にする。
- **`task fe-test-build`**：`fe-coverage`と、`FRONTEND_OTEL_ENABLED=false`を強制した本番ビルドを実行する。
  ビルドの後に`apps/web/dist/assets`を`faro|grafana|opentelemetry|web.?vitals`でgrepし、bundleにFaro、OpenTelemetry JS、Web Vitalsのコードが入っていれば失敗する（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- **`task fe-verify`**：`fe-check`、`fe-knip`、`fe-test-build`（テレメトリを無効にした本番ビルドとgrepを含む）を実行する。
  テストのうち`frontend/apps/web/vite-config.test.ts`は、ZAPの10055-6の除外の`evidence`（`docker/zap/passive.yaml`と`active.yaml`）が配信するCSPと一致することも検査する。
- **`task fe-route-tree-check`**：ビルドで`routeTree.gen.ts`を再生成し、コミット済みの内容と差分があれば失敗する。
  再生成した`fullPaths`がCollectorのroute allowlist（`transform/frontend_validate`の1か所）と一致しなければ、差分の検査より前に失敗する。
- **`task api-client-check`**：Orvalで`packages/api/src/generated`を再生成し、コミット済みの内容と差分があれば失敗する（後述の「API契約」）。

`eslint-plugin-testing-library`はOxlintの`jsPlugins`で読み込み、`apps/*/src`のテストと`src/testing/`に`await-async-queries`、`await-async-utils`、`no-container`、`no-manual-cleanup`、`no-node-access`、`no-unnecessary-act`、`no-wait-for-multiple-assertions`、`no-wait-for-side-effects`、`prefer-find-by`、`prefer-screen-queries`、`prefer-user-event`をerrorで適用する。
Oxlintで読めることを確かめて入れた。依存を1つ足すだけで外せるので、ADRは起こさない。
Vitestは`globals`を渡さず、Testing Libraryの自動cleanupが効かない。そのため`src/testing/setup.ts`の`cleanup()`が唯一の後片付けで、このファイルだけ`no-manual-cleanup`を`off`にする。

フロントエンドのLintの例外と調整、ファイルの範囲ごとの制限、Lintを通る書き方は[フロントエンドのLint設定](../frontend/lint-config.md)にある。

## API契約

契約と生成物の扱いは[ADR-052](../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)、手順は[APIを変更する](../web-api/runbook-api-change.md)を参照する。

- **`task api-gen`**：契約の生成、Spectralの検査、Orvalの再生成を実行する。
- **`task api-check`**：`api-gen`で再生成した契約と生成物を、コミット済みの内容と比べる。
- **`task api-lint`**：コミット済みの`openapi/openapi.yaml`を`.spectral.yaml`で検査する（DB不要）。
- **`task api-lint-rules-test`**：Spectralのルールを`openapi/spectral-fixtures/`の合格例と違反例で検査する。違反例の期待値は`invalid.expected`に「ルール名|JSONパス」で書き、過不足があれば落とす。
- **`task api-client-gen`**：コミット済みの契約からOrvalでAPI clientとMSW handlerを再生成する（DB不要）。
- **`task api-client-check`**：`api-client-gen`で再生成した生成物を、コミット済みの内容と比べる（DB不要）。
- **`task api-breaking`**：`origin/<BASE_REF>`（既定は`main`）の契約とoasdiffで比べ、破壊的変更があれば失敗する。
  baseに契約がなければ比較をとばす。
  環境変数`API_BREAKING_APPROVED=true`（CIではPull Requestのラベル`api-breaking-approved`）のときは、結果をログに残して失敗にしない。
- **`task api-docs`**：契約からRedocly CLIで静的HTMLの設計書`build/api-docs/index.html`を作る。

差分の比較は作業ツリーをindexと比べ、未追跡の生成物も差分として扱う。

## リポジトリ全体

- **`task lint-duplicates`**：生成コードを除く手書きのバックエンドとフロントエンドを、`import`文を除いてjscpdで検査する。
- **`task scan-secrets`**：ステージ済み変更をbetterleaksでスキャンする。
- **`task scan-secrets-all`**：現在のブランチから辿れる履歴をbetterleaksでスキャンする。環境変数`BETTERLEAKS_LOG_OPTS`でgit logの範囲を変えられる（既定は`HEAD`）。
  merge commitの差分も走査するため、タスクが`--diff-merges=first-parent`を常に付ける。betterleaksの既定ではmerge commitを走査せず、コンフリクトの解消だけで入ったtokenを見逃す。
- **`task lint-betterleaks`**：`.betterleaks.toml`の除外に一致するファイルへ、乱数のtokenと`password = '<乱数>'`をstageして本物のbetterleaksで走査し、検出されることを検査する。
  現在のファイルが検出されないことも確かめる。対象は`.betterleaks.toml`の`paths`に一致する追跡ファイルで、設定から求める。
  確かめるのはファイル全体が除外されていないことで、除外した行の厳しさは見ない。
- **`task lint-semgrep`**：Semgrep OSSで静的解析する。
- **`task be-sbom`**：バックエンドのCycloneDX SBOMを生成する。
- **`task scan-vulns`**：バックエンドとフロントエンドの依存関係をTrivyで検査する。
- **`task scan-vulns-backend`**：バックエンドのCycloneDX SBOMをTrivyで検査する。
- **`task scan-vulns-frontend`**：フロントエンドの解決済み依存関係をTrivyで検査する。
- **`task scan-depscan`**：バックエンドとフロントエンドの依存関係をdep-scanで検査する。脆弱性DBは環境変数`DEPSCAN_VDB_HOME`（既定は`~/.cache/depscan-vdb`）に残す（[ADR-095](../adr/ADR-095-scan-dependencies-with-dep-scan-alongside-trivy.md)）。
- **`task scan-depscan-backend`**：バックエンドのCycloneDX SBOMをdep-scanで検査し、レポートを`build/depscan/backend/`に出す。
- **`task scan-depscan-frontend`**：フロントエンドのロックファイルをdep-scanで検査し、レポートを`build/depscan/frontend/`に出す。
- **`task scan-dast`**：slotを取り、隔離した依存を起動してマイグレーションを適用し、backend、`vp preview`を起動して、OWASP ZAPで認証付きのpassive scanを実行して片付ける。
- **`task scan-dast-active`**：同じ環境でactive scanを実行する。
- **`task lint-actions`**：GitHub Actionsワークフローをactionlintで検査する。
- **`task lint-actions-security`**：GitHub Actionsのワークフローとcomposite action（`.github/actions/`）をzizmorで検査する。zizmorは`mise.toml`の版で動き、Dockerを使わない。設定は`.github/zizmor.yml`である。
- **`task docker-available`**：`docker info`が成功すれば0を、失敗すればスキップの理由を出して0以外を返す。Gitフックの中でDockerを使うジョブが、スキップするかを決めるために呼ぶ。
- **`task lint-docker`**：Dockerfileをhadolintで検査する。
- **`task lint-docker-check`**：Dockerfileを`docker build --check`で検査する。
- **`task lint-compose`**：Composeファイルの構文、参照、変数展開を検証する。
- **`task otel-collector-check`**：許可していない属性を含むOTLPのログをCollectorに流し、その属性が除かれ、許可した属性が残ることを確かめる。
  最初に、Collectorのroute allowlistが`routeTree.gen.ts`の`fullPaths`と一致することを確かめる。
  Faroのfixtureもフロントエンドのpipelineに流し、例外、View、LCP、INP、CLSが同じ匿名session IDを持つことを確かめる。
  許可していない属性、URL token、UUIDのsession IDが出口に残らないことも検査する。
  同一オリジンの`/assets/<名前>.js`のstack frameだけが、pathと行と列で残ることも検査する。
  CSP違反の報告のfixtureを`logs/frontend_csp`に流し、許可した4つの属性だけが残り、CSP以外の報告の型、`disposition`のない報告、64 KiBを超える本文が捨てられることも検査する。
  数値でないportと`@`を重ねたuserinfoの`blockedURL`が`csp.blocked`に残らず、`documentURL`と`sourceFile`の値が出口に残らないことも検査する。
  受け口が`application/reports+json`以外の`Content-Type`に401を返し、`text/plain`で送った有効な本文が出口に届かないことも検査する。
  このタスクは`webhook_event`のportを直接叩き、Viteのproxyを通らない。
  Viteのproxyのhop（`/csp-report`と`/collect`の転送と資格情報のheaderの削除）は、`frontend/apps/web/vite-config.test.ts`が偽の受け口で検査する。
  ブラウザがHTTPSの配信から報告を送り、Grafanaに届くまでの経路全体は手動で実測する。
  同じfixtureはtraceも持ち、faro receiverがそれを受け付けることと、出口のどのファイルにもtrace IDが現れないことを確かめる。
  faro receiverを使うtraceのpipelineがないので、spanは捨てられる（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- **`task lint-md`**：`.markdownlint-cli2.yaml`の除外設定に従いMarkdownを検査する。
- **`task lint-md-fix`**：markdownlint-cli2で安全に修正できるMarkdownの問題を修正する。
- **`task ruleset-diff`**：GitHub上のrulesetと`.github/rulesets/main.json`の差分を表示する。認証済みの`gh`が要るため、CIでは動かさず、管理者が手元で使う。
- **`task ruleset-apply`**：`main.json`をGitHub上のrulesetへ反映する。`CONFIRM_RULESET=yes`が要る。手順は[mainブランチの保護設定](../repository/branch-protection.md)にある。
- **`task okf-check`**：OKF適合、内部リンク、孤立文書、変更した文書の責務の見直し合図（250行超の警告）、steering境界、ADRの整合（`adr-lint`）、削除した識別子が残っていないこと（`removed-names-check`）、READMEの技術スタック表のBackendの版が`backend/build.gradle`と一致すること（`readme-versions-check`）、rulesetの必須チェックとdocsの一覧とworkflowの名前の一致（`ruleset-check`）、iweのMCPサーバーの起動コマンドが選ぶ`iwe-mcp.sh`（`iwe-mcp-check`）、iweのMCPサーバーの全ツールが`disabledTools`か残す一覧に入っていること（`iwe-tools-check`）、Taskfile文書同期候補を検査する。
  `removed-names-check`は内部タスクで、`tooling/removed-names/names.txt`に書いた削除した識別子が、`docs/`、`.agents/`、`backend/src/`（生成コードを除く）に残っていないことを確かめる。
  `be-lint`と`skills-check`からも動く。`backend-ci`のlintジョブは`be-lint`を呼ばないので、`check.sh`を直接実行する。
  `check.sh`の自己検査（`tooling/removed-names/test.sh`）は`okf-check`だけが実行する。
  識別子を削除、改名するときは、同じ変更で`names.txt`に足す。検査するのは識別子だけで、古い言い回しは見つけられない。残してよいのは、`names.txt`に書いた判断の履歴（ADRの背景と選択肢）だけである。
  `adr-lint`は内部タスクで、`okf-check`の中でだけ動く。
  `tooling/adr-lint/test.sh`が壊れたfixtureで期待の終了コードを確かめてから、`tooling/adr-lint/adr-lint.mjs`が実際のツリーを検査する。
  失敗にするのは、ADR番号の重複、ADRファイルごとに索引の行がちょうど1つでない状態、索引と本文の状態と日付の不一致、`[ADR-NNN](…ADR-MMM-….md)`のラベルと宛先の番号の不一致である。
  状態は`Superseded by ...`を先頭の語に揃えて比べる。
  番号の欠けは警告にとどめる。
  リンクの検査は`docs/`、`README.md`、`CONTRIBUTING.md`、`.kiro/steering/`、`backend/src`のJavadocとMarkdownを対象にする。
  既知の限界として、`okf-validate.yml`が変更を判定する対象は`backend/src/**`を含まないため、バックエンドのJavadocだけを変えるPull Requestでは検査が動かない。
  ADRの番号を振り直す変更はdocsの変更を伴うため、そこで検出する。
  `ruleset-check`も内部タスクで、`tooling/ruleset-check/test.sh`と`tooling/ruleset-sync/test.sh`の後に`ruleset-check.mjs`が動く。
  `.github/rulesets/main.json`の必須チェックを、`branch-protection.md`の一覧とworkflowの`name`に突き合わせる。
  GitHub上のrulesetとの一致は検査しない。
- **`task ci-gate-check`**：`ci.yml`の`CI gate`が、`pull_request`で動く検査を取りこぼしていないかを検査する。
  あわせて、setup-miseを使うworkflowの`paths:`に`mise.toml`、`mise.lock`、`.github/actions/setup-mise/**`があることと、`mise.lock`を通さずに道具を入れるaction（`ci-gate-check.py`の`BANNED_USES`）を使っていないことを検査する（[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）。
  `tooling/ci-gate-check/test.sh`と、PRの変更を判定する`tooling/ci-changes/test.sh`を先に実行してから、`ci-gate-check.py`が実際の`.github/workflows/`を検査する。
  `changes`ジョブは`tooling/ci-changes/changed.sh`にglobを渡して判定する。globは`paths:`と同じ書き方で、`ci.yml`の変更でも`true`になる。
  失敗にする状態は`test.sh`のケースが正で、この文書には写さない。
  Python 3とPyYAMLが要る（GitHubのランナーには入っている。ローカルでは`sudo apt install python3-yaml`）。CIだけで実行し、Lefthookには入れない。
- **`task mise-lock-check`**：`mise.toml`の全ての道具の版が`mise.lock`に載っていることを、`mise install --locked --dry-run`で検査する（[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）。
  CIのjobは使う道具だけを入れるので、ほかの道具の版だけを上げてlockを作り直さない変更をここで止める。CIだけで実行する。
- **`task skills-check`**：`skills-lock.json`と`.kiro/.kiro-plugins-installed`の記録が、`.agents/skills`の実体、`.kiro/skills`のリンク、`kiro/install.sh`が入れたファイルと合っているかを検査する。
  入れたファイルへの書き込みを止めるフックの自己検査（`.kiro/hooks/block-generated-writes.test.sh`）、エージェントの`git commit`と`git push`の直接実行を止めるフックの自己検査（`.kiro/hooks/block-agent-git.test.sh`）、`tooling/skills-check/test.sh`を先に実行してから、`tooling/skills-check/skills-check.mjs`が実際の木を検査する。
  失敗にする状態は`tooling/skills-check/test.sh`のケースが正で、この文書には写さない。
  手順の正文は[スキルの取得元と更新](../agents/skill-sources.md)である。
- **`task release-check`**：release-pleaseのmanifest、版ファイル、Gradle版、設定の一致を検証する。
- **`task agent-git-test`**：`task agent-commit`、`task agent-push`、`task agent-wait`の本体を、`tooling/agent-git/test.sh`のケースで検査する。
- **`task test-slot-test`**：`tooling/test-slot/test-slot.sh`のslotの割り当て、待ち、回収、環境変数とenvファイルの値を、偽のdockerで検査し、`docker/keycloak/realm.json`のwebのoriginの数がslotの数と一致することを確かめる。
  ケースは`tooling/test-slot/test.sh`が正で、この文書には写さない。
- **`task lefthook-install-test`**：`npm ci`の`prepare`（`tooling/lefthook-install/prepare.mjs`）が、linked worktreeからもmainのworktreeのlefthookでフックを入れることを検査する。
  mainのworktreeの場所が分からないときと、そこにlefthookが無いときは、`prepare`が失敗することも確かめる。
  検査には一時のgitリポジトリと、lefthookの代わりのスクリプトを使う。
  `lefthook.yml`の`no_auto_install`も`lefthook dump`で確かめる。
  ケースは`tooling/lefthook-install/test.sh`が正である。
- **`task formal-verify-test`**：検証スキルが使う`tooling/formal-verify/check.sh`と`tooling/formal-verify/mutants.py`を、`tooling/formal-verify/fixtures/counter/`の小さなTLA+のモデルで検査する。
  ケースは`tooling/formal-verify/test.sh`が正で、この文書には写さない。
  初回は、TLCの取得にネットワークが要る。
  Javaは`JAVA_HOME`か、なければPATHの`java`（通常は`mise.lock`の版を選ぶshims、[ADR-090](../adr/ADR-090-pin-cli-tools-with-mise-lock.md)）を使う。
- **`task formal-verify`**：`.agents/skills/formal-verify-*`の全スキルについて、`sync-check.sh`で同期コミットが`HEAD`の祖先として解決できることを、`check.sh`で全設定を、`mutants.py`で全変異を検査する。
  数分かかる。
  CIの`formal-verify.yml`が`formal-verify-test`とともに実行する。
  対象のパスはworkflowが正である。
  CIは、コードとモデルのずれを検出しない。
  検証スキルの手順の正文は[形式検証スキルの使い方](../agents/formal-verification.md)である。
- **`task lint-taskfile`**：Task本体でTaskfileのYAML構文とスキーマ構造を検証する。
  `tooling/taskfile-cleanup/`のfixtureで、後片付けの定型が本体の終了コードを優先し、前段が失敗しても後段の後片付けを走らせることを検査する。
  `tooling/openapi-drift/test.sh`は、契約の書き出し漏れと追跡されていないpathによる`be-openapi-check`の素通りを、一時のgitリポジトリで検査する。ケースがこの文書の正である。
  `tooling/task-calls/task-calls.sh`は、フックとワークフローが呼ぶタスク名を検査する。
  対象は、`lefthook.yml`と`.github/workflows/`のYAMLにある`task <名前>`である。
  名前が`task --list-all`のタスクにも別名にも無いと失敗する。
  失敗にする状態は`tooling/task-calls/test.sh`のケースが正で、この文書には写さない。
  読み違える書き方と見落とす書き方は、`task-calls.sh`の`ponytail:`の注記にある。
- **`task lint-lefthook`**：`lefthook.yml`の`run`を`echo`に置き換えた設定で本物のlefthookを動かし、glob付きのjobが意図したファイルで選ばれることを検査する。
  `docs/**/*.md`のような`a/**/b`の形の`**`は0階層に一致しないため、ディレクトリ直下のファイル（`docs/index.md`、`backend/build.gradle`）を中心に少数だけ期待を置く。
  見るのはglobだけで、jobの`run`内の`{files}`の参照は見ない。期待は`tooling/lefthook-glob/test.sh`が正で、全jobには広げない。
- **`task lint-scratch-git`**：自己検査の`tooling/*/test.sh`と`.kiro/hooks/*.test.sh`が、gitを実行する前に[`tooling/scratch-git/scratch-git.sh`](../../tooling/scratch-git/scratch-git.sh)を`source`しているかを検査する（`source`しないファイルでは、どの行のgitも失敗にする）。
  失敗にする書き方は`tooling/scratch-git/test.sh`のケースが正で、この文書には写さない。
  見落とす書き方は、`tooling/scratch-git/scratch-git-check.sh`の`ponytail:`の注記にある。
- **`task adr-check`**：判断が絡む変更にADRが伴うかを確認する。
  `frontend/`の変更のうち、テストファイル（`*.test.*`、`*.spec.*`）、`frontend/apps/*/e2e/`、`frontend/lint/fixtures/`、`frontend/apps/*/src/testing/`は対象から外す。
  警告は止めず、`ADR_STRICT=1`で失敗にし、`ADR_ACK=1`で抑制する。
- **`task test-weakening`**：`origin/main`とのmerge baseから`HEAD`までで、テストファイルの削除とテストケースの減少と、過去のイベントのfixtureの変更、削除、renameを警告する（[人が必ず読むPull Request](../pull-request/review-request-and-merge.md#人が必ず読むpull-request)の「テストを弱める」の目印にする）。
  対象のファイルと数え方は`tooling/test-weakening/check.sh`の冒頭が正である。数え方は概算で、renameは削除に数えず、テストでない名前へのrenameは削除に数える。
  イベントのfixtureは`backend/src/test/resources/events/`の下のファイルで、追加は警告しない。fixtureを変えないことは`EventContractTest`では確かめられないため、この警告とレビューで守る（[ADR-137](../adr/ADR-137-guard-event-json-compatibility-with-versioned-fixtures.md)）。
  警告は標準エラーに出し、終了コードは常に0にする。`TESTS_ACK=1`で抑制し、比較の起点が無ければ理由を出して通す。
  `.skip`、`.only`、理由のない`@Disabled`、coverageの低下は別の検査が止めるので、ここでは扱わない。
  自己検査は`tooling/test-weakening/test.sh`で、`task test-weakening-test`で実行する。`task ci-gate-check`もCIで実行する。

Knipとjscpdの採用理由は[ADR-035](../adr/ADR-035-adopt-jscpd-and-knip-quality-gates.md)を、jscpdがimport文を比べず、閾値を2.0%にする理由は[ADR-106](../adr/ADR-106-ignore-import-statements-in-jscpd.md)を参照する。
DASTの検出ではタスクを失敗させず、起動、ログイン、CSRFの前提確認の失敗だけで失敗させる（[ADR-056](../adr/ADR-056-run-authenticated-dast-with-zap-in-ci.md)）。
OKF検査の採用理由は[ADR-036](../adr/ADR-036-adopt-okf-for-docs-knowledge-bundle.md)と[ADR-038](../adr/ADR-038-route-steering-to-docs-knowledge.md)を参照する。

betterleaksの誤検知の除外の書き方は[betterleaksの誤検知の除外](betterleaks-exclusions.md)にある。

## Gitフック対応

- **commit-msg（すべて）**：commitlintを実行する。
- **pre-commit（すべて）**：`scan-secrets`を実行する。
- **pre-commit（OpenAPI契約、`.spectral.yaml`、Orvalの設定、`frontend/apps/web/package.json`、生成物の変更）**：`api-lint`（`docker info`が失敗すればスキップ）と`api-client-check`を実行する。
- **pre-commit（フロントエンドか`tooling/e2e-seed-lint/`の変更）**：`fe-check`を実行する。
  前項と同じgroupで順に実行し、Orvalの再生成と型検査を同時に走らせない。
- **pre-commit（Dockerfileの変更）**：`lint-docker`相当と`lint-docker-check`相当を実行する。
- **pre-commit（Composeファイルの変更）**：`lint-compose`相当を実行する。
- **pre-commit（Markdownの変更）**：変更ファイルへ`lint-md`相当を実行する。
- **pre-commit（docs、steering、Taskfile、README、`tooling/adr-lint/`、`tooling/removed-names/`、`tooling/readme-versions/`、`backend/build.gradle`、`.kiro/settings/mcp.json`、`tooling/iwe-mcp/`、`tooling/iwe-tools/`、`.github/rulesets/`、`.github/workflows/`、`tooling/ruleset-check/`、`tooling/ruleset-sync/`の変更）**：`okf-check`を実行する。
- **pre-commit（`.agents/skills/`、`.kiro/`、`skills-lock.json`、`tooling/skills-check/`の変更）**：`skills-check`を実行する。
- **pre-commit（Taskfile、`lefthook.yml`、GitHub Actionsワークフロー、`tooling/taskfile-cleanup/`、`tooling/task-calls/`、`tooling/openapi-drift/`の変更）**：`lint-taskfile`を実行する。
- **pre-commit（`tooling/agent-git/`の変更）**：`agent-git-test`を実行する。
- **pre-commit（`tooling/test-slot/`、`docker/keycloak/realm.json`、`docker/compose-test.yml`の変更）**：`test-slot-test`を実行する。
- **pre-commit（`lefthook.yml`か`tooling/lefthook-glob/`の変更）**：`lint-lefthook`を実行する。
- **pre-commit（`.betterleaks.toml`、`.betterleaksignore`、`tooling/betterleaks/`の変更）**：`lint-betterleaks`を実行する。
- **pre-commit（`package.json`、`lefthook.yml`、`tooling/lefthook-install/`の変更）**：`lefthook-install-test`を実行する。
- **pre-commit（`tooling/formal-verify/`の変更）**：`formal-verify-test`を実行する。
- **pre-commit（`tooling/*/test.sh`、`.kiro/hooks/*.test.sh`、`tooling/scratch-git/`の変更）**：`lint-scratch-git`を実行する。
- **pre-commit（`tooling/test-weakening/`の変更）**：`test-weakening-test`を実行する。
- **pre-commit（`backend/src/main/java/`、`backend/src/main/resources/db/`、`tooling/session-sql-lint/`、`tooling/db-clock-lint/`の変更）**：`lint-session-sql`と`lint-db-clock`を実行する。
- **pre-push（すべて）**：`adr-check`、`test-weakening`、`scan-secrets-all`を実行する。
- **pre-push（バックエンドのJavaまたはGradle変更）**：`be-lint`相当を実行する。全体のテスト（`task test`）はpre-pushでは実行せず、Backend CIが流す（[ADR-128](../adr/ADR-128-run-backend-tests-in-ci-not-in-pre-push.md)）。
- **pre-push（フロントエンドの変更）**：`fe-test-build`を、Vitestのdot reporterと、報告を出さない`none`のcoverage reporterで実行する。
  下限の検査は報告の形式によらず働く。
- **pre-push（GitHub Actionsのワークフローかcomposite actionの変更）**：`lint-actions`相当と`lint-actions-security`相当を実行する。

Gitフックの条件とコマンドは[`lefthook.yml`](../../lefthook.yml)を正とする。
ブランチがglobに当たるファイルを変えていなくても、`lefthook run <フック> --job <job> --file <globに当たるパス> --force`で一つのjobだけを実行できる。
変更前の設定と比べるときは、`LEFTHOOK_CONFIG=<別のlefthook.yml>`を付けて同じコマンドを実行する。
Lefthookは版の表示、skipの行、`EXECUTE`の行を省き、jobの出力と要約を出す（`output`）。
すべてを出すときは、`LEFTHOOK_OUTPUT`に`meta,summary,success,failure,execution,execution_out,execution_info,skips`を渡す。
pre-pushのjobは順番に実行し、失敗した時点で止める。
バックエンドイメージのビルドはpre-pushでは実行せず、Docker CI（`hadolint.yml`）の`backend-image`がPull Requestごとに実行する。
pre-pushの「変更」は、`origin/main`とのmerge-baseからの差分で判定する。
2回目以降のpushでも、ブランチ全体の変更に対して検査が走る。
`origin/main`が無くmerge-baseを取れないときは、全ファイルを対象にして全検査を走らせる。
判定の理由は`lefthook.yml`の`files`のコメントにある。

## CI対応

- **`backend-ci.yml`**：`be-lint`相当、`lint-session-sql`、`lint-db-clock`、`be-verify-migrations`、`be-test`、`be-openapi-check`を実行する。
  compose-testを止めた後に`test-deps-leftover-check`を実行する。
  契約は`be-test`が書き出し、`be-openapi-check`は`OpenApiContractTest`を再実行しない（[ADR-064](../adr/ADR-064-write-openapi-contract-from-test-task-in-ci.md)）。
  Gradle User Homeは`setup-gradle`の`cache-provider: external`にして、`actions/cache`のrestoreとsaveで扱う。
  keyはビルドファイルのhashで、restore-keysにより直近のmainのcacheを復元する。
  saveはmainのpushで、restoreが完全一致でなかったときだけ行う（[ADR-063](../adr/ADR-063-restore-gradle-cache-with-restore-keys-on-mit-caching.md)）。
- **`mutation-test.yml`**：Backend CIと同じパスの変更を含むPR、mainへのpush、手動実行で`mutation-test`を実行し、PITのレポートをartifactにする。
  PRでは`PIT_CHANGED_SINCE=origin/<base>`を渡して差分のクラスだけを、pushと手動実行では全体を変異させる。
  ジョブのtimeoutはPRで60分、pushと手動実行で180分とする。
  PRの実行は後のpushが取り消す。mainの実行は、実行中なら取り消さず、待ちのものを後のpushや手動実行が置き換える。
  スコアの閾値を持たず、PITが完走しないときだけ失敗する。
  Backend CIのtestと同じGradle cacheをread-onlyで復元する。
  必須チェックにしない（[ADR-076](../adr/ADR-076-run-mutation-tests-per-change-and-tighten-coverage-gates.md)）。
- **`codeql.yml`**：actions、java-kotlin（autobuild）、javascript-typescriptを解析する（[ADR-083](../adr/ADR-083-manage-codeql-as-advanced-setup-with-explicit-build-mode.md)）。
  java-kotlinはBackend CIのtestと同じGradle cacheをread-onlyで復元し、Plugin Portalからのプラグイン取得で落ちないようにする。
- **`frontend-ci.yml`**：`fe-verify`、`fe-route-tree-check`、`api-client-check`を実行する。
  `fe-verify`の`fe-check`が`lint-e2e-seed`を実行するため、`tooling/e2e-seed-lint/`の変更でも動く。
- **`api-contract.yml`**：`api-lint`、`api-lint-rules-test`、`api-breaking`、`api-docs`を実行し、設計書をartifactにする。
  mainへのpushでは設計書をGitHub Pagesに公開する。
- **`betterleaks.yml`**：`lint-betterleaks`と`scan-secrets-all`を実行する。PRでは`BETTERLEAKS_LOG_OPTS`にbaseからheadまでの範囲を渡す。
- **`static-analysis.yml`**：`lint-semgrep`相当、`lint-duplicates`、`lint-taskfile`、`lint-lefthook`、`agent-git-test`、`lefthook-install-test`、`lint-scratch-git`、`test-slot-test`を実行する。
  `test-slot-test`は振る舞いのテストだが、常に動くこのworkflowに置く（[ADR-087](../adr/ADR-087-gate-required-checks-with-single-aggregate-job.md)）。
  Code ScanningはSARIFの`suppressions`を読まないため、`nosemgrep`で抑止した検出をSARIFから除いてから送る。
- **`trivy.yml`**：`scan-vulns`相当を実行する。Trivy本体の版は`SCAN_TRIVY_VERSION`で明示し、Taskfileの`aquasec/trivy`の版とそろえる（[ADR-097](../adr/ADR-097-keep-trivy-and-align-its-version.md)）。
- **`depscan.yml`**：`scan-depscan-backend`と`scan-depscan-frontend`を実行し、レポートをartifactにする。SARIFを出さないのでCode Scanningには送らない。
- **`dast.yml`**：PRで`scan-dast`、週1回のscheduleと手動実行で`scan-dast-active`を実行し、Informationalを除いたSARIFをCode Scanningに送る。
- **`actionlint.yml`**：`lint-actions`相当を実行する。
- **`ci.yml`**：`pull_request`で動く検査を呼び出し、集約ジョブ`CI gate`が全ジョブの`success`を確かめる。`ci-gate-check`も実行する（[ADR-087](../adr/ADR-087-gate-required-checks-with-single-aggregate-job.md)）。
- **`zizmor.yml`**：`lint-actions-security`相当を実行する。
- **`hadolint.yml`**：`lint-docker`相当、`lint-docker-check`相当、バックエンドイメージのビルドと起動確認を実行する。
- **`compose-config.yml`**：`lint-compose`相当を実行する。
- **`otel-collector.yml`**：`otel-collector-check`を実行する。
- **`markdownlint.yml`**：`lint-md`相当を実行する。
- **`okf-validate.yml`**：`okf-check`を実行する。
- **`skills-check.yml`**：`skills-check`を実行する。
- **`formal-verify.yml`**：`formal-verify-test`と`formal-verify`を実行する。
- **`release-please.yml`**：`release-check`を実行する。
- **`e2e.yml`**：`task e2e`を実行し、`test-deps-leftover-check`でcompose-testのコンテナとvolumeが残っていないことを確かめる。
  Backend CIのtestと同じGradle cacheをread-onlyで復元する。
  必須チェックにしない（[ブランチ保護](../repository/branch-protection.md)）。

各ワークフローの実装は[`.github/workflows/`](../../.github/workflows/)を正とする。
