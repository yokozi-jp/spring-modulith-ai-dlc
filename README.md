<!-- markdownlint-disable-next-line MD041 -->
<div id="top"></div>

# spring-modulith-ai-dlc

## 目次

1. [プロジェクトについて](#プロジェクトについて)
2. [AI harnessの構成](#ai-harnessの構成)
3. [Grill駆動開発](#grill駆動開発)
4. [技術スタック](#技術スタック)
5. [ディレクトリ構成](#ディレクトリ構成)
6. [開発環境構築](#開発環境構築)
7. [開発コマンド](#開発コマンド)
8. [Lint・テスト](#lintテスト)
9. [設計判断の記録（ADR）](#設計判断の記録adr)
10. [ドキュメント管理（iwe / OKF）](#ドキュメント管理iwe--okf)

## プロジェクトについて

AI コーディングエージェントが安全に、生産的に働ける環境（**AI harness**）を整えることを主題にしたプロジェクトです。
業務コードを書く前に、エージェントの変更を機械的に検査する仕組みと、エージェントが従う規約の置き場を用意しています。

Spring Modulith は、その一部として選んだ道具です。
モジュール間の境界と依存をテストで検証できるため、エージェントが境界を越える変更を書いても、人のレビューより前に検出できます。
言語、フレームワーク、ツールは、同じ基準で「変更を機械的に検査できるか」を見て選んでいます。

業務ドメインのモジュールはこれから追加していきます。

## AI harnessの構成

harness は、変更が通る複数の関門と、規約の置き場で構成します。

- **構造の検査**：ArchUnit と Spring Modulith の検証テストで、モジュール境界と依存方向を検査します（[ADR-001](docs/adr/ADR-001-adopt-spring-modulith-modular-monolith.md)）。
  Spotless、PMD、SpotBugs、Error Prone、Oxlint、Knip が、書き方の規約と未使用コードを検査します。
- **契約の検査**：OpenAPI 契約をコミットし、Spectral、oasdiff、Orval の再生成で差分と破壊的変更を検査します（[ADR-052](docs/adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)）。
- **Git フック（Lefthook）**：commit-msg で commitlint を実行します。
  pre-commit では betterleaks のシークレットスキャン、フロントエンドと API 契約の検査、hadolint、Docker ビルド検査、compose config、markdownlint、OKF 検査を実行します。
  pre-push では ADR 検査、betterleaks の全履歴スキャン、フロントエンドのテストとビルド、バックエンドの lint とテスト、backend イメージのビルド、actionlint、zizmor を実行します。
- **CI（GitHub Actions）**：Backend CI、Frontend CI、E2E、Docker CI（hadolint）、Secret Scan、Static Analysis（Semgrep）、Trivy による脆弱性スキャン、DAST（OWASP ZAP）、OKF 検証、API Contract（破壊的変更の検出）を実行します。
  リリースは release-please、依存更新は Dependabot が担います。
- **steering と docs**：規約の正文は `docs/` に置き、`.kiro/steering/` は読む文書への案内だけを持ちます（[ADR-038](docs/adr/ADR-038-route-steering-to-docs-knowledge.md)）。
- **ADR**：設計判断は [ADR](#設計判断の記録adr) に残し、pre-push の `task adr-check` で記録漏れに注意喚起します。
- **Taskfile**：すべての検査とビルドを `task <タスク名>` で呼べる単一の入口にします（[ADR-010](docs/adr/ADR-010-adopt-task-as-project-task-runner.md)）。
  フック、CI、手元の実行が同じタスクを呼ぶため、結果が食い違いません。

各ゲートの対応は [Lint・テストのリファレンス](docs/tooling/lint-and-test.md) にまとめています。

## Grill駆動開発

[Matt Pocock のスキル](https://github.com/mattpocock/skills)を導入し、**grill 駆動開発**で進められます。
grill は、エージェントに未決事項を質問させ、答えながら設計を詰めていく対話です。
実装の前に曖昧さをなくし、確定した用語と判断を docs と ADR に残します。

```text
/grill-with-docs
  ↓  必要なら /handoff → /prototype → /handoff
/to-spec
  ↓
/to-tickets
  ↓
チケットごとに新しいセッションで /implement（内部で /tdd と /code-review）
```

- `/grill-with-docs`：アイデアや計画について、エージェントが未決事項を一つずつ質問します。
  答えながら設計を詰め、確定した用語と設計判断を docs と ADR に記録します。
- `/to-spec`：ここまでの対話を、追加の質問なしで仕様にまとめ、Issue tracker に登録します。
- `/to-tickets`：仕様や計画を、単独で実装できる小さなチケットに分けます。
  チケット間の依存関係（どれが先か）も書きます。
- `/implement`：チケット一つを実装します。
  `/tdd` でテストから小さく進め、最後に `/code-review` でプロジェクト規約と仕様への適合を確認します。

スキルは `.agents/skills/` にあります。
どのスキルを使うか迷うときは `/ask-matt` に状況を渡します。
プロジェクトの docs と ADR の規約は、外部スキル内の記述より優先します。

手順の詳細は [Matt Pocock スキル運用](docs/agents/matt-pocock-skills/workflow.md)、導入の判断は [ADR-039](docs/adr/ADR-039-adopt-matt-pocock-skills-workflow.md) にあります。

## 技術スタック

| 分類             | 技術                       | バージョン | 用途                                           |
| ---------------- | -------------------------- | ---------- | ---------------------------------------------- |
| Backend          | Java (Amazon Corretto)     | 25         |                                                |
| Backend          | Spring Boot                | 4.1.1      | Web、Security（OIDC Client）、Session（Redis） |
| Backend          | Spring Modulith            | 2.1.1      | モジュール境界の検証                           |
| Backend          | jOOQ                       | 3.21.8     | スキーマから生成する型安全な SQL               |
| Backend          | Liquibase                  |            | DB マイグレーション                            |
| Backend          | ArchUnit                   | 1.5.0      | アーキテクチャテスト                           |
| Frontend         | React                      | 19.3.0     |                                                |
| Frontend         | TypeScript                 | 7.0.x      |                                                |
| Frontend         | TanStack Router            | 1.170.38   | ファイルベースルーティング                     |
| Frontend         | TanStack React Query       | 5.103.2    | server state                                   |
| Frontend         | TanStack React Form        | 1.33.5     | form state                                     |
| Frontend         | TanStack React Table       | 9.2.4      | テーブル                                       |
| Frontend         | Zod                        | 4.6.5      | スキーマ検証                                   |
| Frontend         | i18next                    | 26.4.2     | 多言語対応                                     |
| Frontend         | Orval                      | 8.36.0     | OpenAPI から API client と MSW handler を生成  |
| Frontend         | Tailwind CSS               | 4.3.3      | スタイリング                                   |
| Frontend         | VitePlus                   | 0.3.3      | ビルド、開発サーバー、Oxlint（`vp`）           |
| Frontend         | Vitest                     | 4.1.11     | 単体テスト                                     |
| Frontend         | MSW                        | 2.15.0     | API のモック                                   |
| Frontend         | Playwright                 | 1.63.0     | E2E テスト                                     |
| Frontend         | Node.js                    | 24.21.0    |                                                |
| Frontend         | pnpm                       | 11.21.0    |                                                |
| インフラとツール | PostgreSQL                 | 18         |                                                |
| インフラとツール | Redis                      | 7.x        | 分散セッション                                 |
| インフラとツール | Keycloak                   | 26.7.3     | OIDC の認可サーバー                            |
| インフラとツール | Grafana OpenTelemetry LGTM | 0.32.1     | 可観測性データの集約                           |
| インフラとツール | OpenTelemetry Collector    | 0.161.0    | ログの属性の絞り込み                           |
| インフラとツール | Go (betterleaks 実行用)    | 1.27.x     |                                                |
| インフラとツール | Task                       | 3.53.1     | タスクランナー                                 |

ログ相関、PII、保持は[可観測性データの規約](docs/observability/conventions.md)、フロントエンドの構成は[フロントエンドアーキテクチャ](docs/frontend/architecture.md)を参照してください。
その他のパッケージのバージョンは `backend/build.gradle` と `frontend/package.json` を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## ディレクトリ構成

```text
.
├── .agents/          # スキル定義（.agents/skills）
├── .github/          # GitHub 設定
│   ├── ISSUE_TEMPLATE/   # Issue テンプレート
│   ├── workflows/        # GitHub Actions（Lint・シークレットスキャン等）
│   ├── CODEOWNERS        # レビュー担当者の自動割り当て
│   ├── PULL_REQUEST_TEMPLATE.md # Pull Request テンプレート
│   └── dependabot.yml    # 依存関係の自動更新設定
├── .kiro/            # Kiro 設定
│   ├── hooks/            # エージェントのフック
│   ├── settings/         # Kiro CLI と MCP サーバーの設定
│   ├── skills/           # .agents/skills へのシンボリックリンク（Kiro の検出用）
│   └── steering/         # steering（docs への案内）
├── .vscode/          # VSCode 設定
├── backend/          # Spring Boot アプリケーション
├── docker/           # Docker 関連ファイル
│   ├── initdb/           # PostgreSQL 初期化スクリプト（スキーマ作成）
│   ├── keycloak/         # Keycloak realm 定義（起動時インポート）
│   ├── otel-collector/   # OpenTelemetry Collector の設定（ローカルと本番で共有）と検査データ
│   ├── compose.yml       # 開発用スタック
│   └── compose-test.yml  # テスト用の隔離スタック
├── docs/             # ドキュメント（iwe / OKF のナレッジグラフ）
│   ├── .iwe/             # iwe の設定と OKF スキーマ
│   ├── adr/              # Architecture Decision Records（設計判断の記録）
│   ├── agents/           # エージェントスキル用の設定（Issue tracker、Triage ラベル、ドメイン文書）
│   ├── backend/          # バックエンドの規約（アーキテクチャ、テスト）
│   ├── code-review/      # コードレビューの規約
│   ├── container/        # コンテナ（Dockerfile、Compose）の規約
│   ├── database/         # データベース（マイグレーション、jOOQ、接続）の規約
│   ├── datetime/         # 日時とタイムゾーンの規約
│   ├── e2e/              # E2E テスト（Playwright）の規約
│   ├── frontend/         # フロントエンドの規約
│   ├── integration/      # システム連携と非同期処理の規約
│   ├── knowledge/        # ナレッジ管理（docs と steering の役割分担）
│   ├── local-env-setup/  # 開発環境構築手順・スクリプト
│   ├── nfr/              # 非機能要件
│   ├── observability/    # 可観測性とログの規約
│   ├── performance-test/ # 性能テスト
│   ├── principles/       # アーキテクチャ原則
│   ├── pull-request/     # Pull Request の規約
│   ├── report/           # 帳票
│   ├── repository/       # リポジトリ運用（ブランチ保護、リリース）
│   ├── tooling/          # 開発ツール（Taskfile、フック、CI、Lint）の規約
│   ├── web-api/          # Web API の設計と契約
│   ├── writing/          # 日本語の技術文書の書き方
│   └── index.md          # docs の入口
├── frontend/         # React + TanStack Router + TypeScript の SPA（VitePlus、pnpm）
├── infrastructure/   # インフラ定義（未整備）
├── openapi/          # コミット済みの OpenAPI 契約（openapi.yaml、生成物）と Spectral のルールの fixture
├── .betterleaks.toml # betterleaks（シークレットスキャナ）設定
├── .editorconfig     # エディタ共通設定
├── .env.example      # 環境変数のサンプル
├── .env.test.example # テスト用の環境変数のテンプレート（非機密ダミー。.env.test へコピーして使う）
├── .gitignore        # Git 追跡除外設定
├── .hadolint.yaml    # hadolint（Dockerfile リンタ）設定
├── .jscpd.json       # jscpd（重複コード検査）設定
├── .markdownlint-cli2.yaml # markdownlint-cli2（Markdown リンタ）設定
├── .release-please-manifest.json # release-please が追跡する直近の版
├── .snyk             # Snyk のスキャン除外ポリシー（プロダクションコード以外を除外）
├── .spectral.yaml    # Spectral（OpenAPI 契約リンタ）設定
├── AGENTS.md         # エージェント共通の設定（スキルが参照する docs の案内）
├── CHANGELOG.md      # リリースノート（release-please が生成）
├── CONTRIBUTING.md   # 変更手順、ブランチ運用、コミット規約
├── LICENSE           # ライセンス
├── README.md         # プロジェクト概要（このファイル）
├── SECURITY.md       # 脆弱性の非公開報告方法
├── Taskfile.yml      # 開発コマンド定義
├── commitlint.config.mjs # commitlint 設定（Conventional Commits 検証）
├── lefthook.yml      # Git フック定義（Lefthook）
├── package.json      # Lefthook・commitlint 導入用（ルート）
├── package-lock.json # ルート npm 依存の lockfile
├── release-please-config.json # release-please の設定
├── skills-lock.json  # スキルのバージョン固定（lock）
└── version.txt       # simple strategy が使う主版ファイル
```

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発環境構築

[開発環境構築ガイド](docs/local-env-setup/setup.md) を参照してください。
ローカル環境の初回の起動手順と各サービスのポートは [開発ワークフロー](docs/tooling/dev-workflow.md) を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発コマンド

ビルド、テスト、SBOM 生成などの各種コマンドは [`Taskfile.yml`](Taskfile.yml) にまとめています。

```bash
task <タスク名>
```

引数なしの`task`、`task help`、または`task --list`で、公開タスクの一覧を表示します。

初回の環境構築は [開発環境構築ガイド](docs/local-env-setup/setup.md)、初回の起動と日常の流れは [開発ワークフロー](docs/tooling/dev-workflow.md) に従ってください。

日々の開発で使う入口タスクは次の五つです。

- **`task dev`**：依存サービスを起動してバックエンドを起動（日々の開発の入口）。
- **`task check`**：素早いローカル確認（バックエンドの静的解析）。
- **`task fe-verify`**：フロントエンドの静的解析、未使用コード検査、テスト、本番ビルド。
- **`task lint-duplicates`**：フロントエンドとバックエンドの手書きコードの重複検査。
- **`task verify`**：push 前のバックエンド総合ゲート（静的解析、OpenAPI 契約検査と、使い捨てDBでのマイグレーション検証とテスト、CI と同じ内容）。

API の Controller や DTO を変えたときは `task api-gen` で OpenAPI 契約と Orval の生成物を再生成し、同じコミットに含めます（[API を変更する](docs/web-api/runbook-api-change.md)）。

Docker Compose の操作（サービスの起動、停止、状態確認、Keycloak の realm 再投入）や、「いつ、どのコマンドを、どの順で使うか」のシナリオ別の手順は [開発ワークフロー](docs/tooling/dev-workflow.md) を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## Lint・テスト

静的解析、シークレットと脆弱性のスキャン、テストは、いずれも [`Taskfile.yml`](Taskfile.yml) のタスクとして実行できます（`task <タスク名>`）。
各タスクの一覧と内容、Git フックと CI での自動実行の対応は [Lint・テストのリファレンス](docs/tooling/lint-and-test.md) にまとめています。

### フロントエンド

- 手動整形には `task fe-format` を使い、変更中の確認には `task fe-check` を使います。
- `task fe-check` はOxlintからrepository-localのsecurity rule、`@shadcn/lint`、`eslint-plugin-better-tailwindcss` も実行します。
- Tailwind CSSと共有UI componentのdesign-system規則に加え、`dangerouslySetInnerHTML` と生のDOM HTML APIによる任意HTML描画をblocking検査します。
- フロントエンド変更時は `task fe-verify` で静的解析、Knipによる未使用コード検査、テスト、本番ビルドを実行します。
- `task fe-route-tree-check` はビルドで `routeTree.gen.ts` を再生成し、コミット済みの内容との差分があれば失敗させます。
  Collector の route allowlist が再生成した `fullPaths` と一致しない場合も失敗させます。
- `task fe-doctor`は手動のReact診断とFrontend CIで実行し、15分以内に完了しない場合は検査を失敗させます。
- React Doctorのwarningとerrorはどちらもblockingとし、Frontend CIを停止します。
- LefthookはFrontend変更を検出すると、pre-commitで `task fe-check`、pre-pushで `task fe-test-build` を実行します。
- Frontend CIはPull Requestと `main` へのpushで `task fe-verify`、`task fe-route-tree-check`、`task api-client-check`、`task fe-doctor` を実行し、Knip、生成済みroute treeとAPI clientの一致、React診断、全体branch coverage 85%を強制します。
- coverageレポートは `task fe-coverage` で確認でき、CIでは14日間artifactとして保存します。

### E2E

- `task e2e` は frontend と backend イメージをビルドし、compose-test で依存サービスと backend を起動して migration を適用し、Vite preview に対して Playwright を実行してから後片付けします。
- 開発用の Vite（5173）と Keycloak（8080）を止めてから実行します。
  失敗した環境を調べるときは `E2E_KEEP_ENV=1 task e2e` で残せます（CI では常に片付けます）。
- CI の `E2E tests 🎭` は対象パスの変更でだけ起動し、必須チェックにはしていません。
- 書き方と前提は [E2E テストの方針と書き方](docs/e2e/testing-strategy.md) を参照してください。

### API 契約

- `task api-gen` はテスト用の依存を起動し、`openapi/openapi.yaml` の生成、Spectral の検査、Orval による `frontend/src/api/generated` の再生成を実行します。
- `task api-check` は再生成した契約と生成物がコミット済みの内容と一致するかを検査します。
- `task api-client-check` は DB なしで Orval の生成物だけを再生成して検査し、pre-commit と Frontend CI で実行します。
- `task be-openapi-check` は直前の `task be-test` が書き出した契約を検査し、Backend CI で実行します。
- `task api-breaking` は `origin/main` の契約と oasdiff で比べ、破壊的変更があれば失敗させます。
  意図した変更は Pull Request にラベル `api-breaking-approved` を付け、本文に理由を書きます。
- `task api-docs` は Redocly CLI で静的 HTML の設計書を `build/api-docs/index.html` に作ります。
  main へのマージで GitHub Pages に公開します。

### 重複コード検査

- `task lint-duplicates` はTanStack RouterとjOOQの生成コードを除いたFrontendとBackendの手書きソースをjscpdで検査します。
- 既存の重複行率3.19%を基準に3.2%を上限とし、既存cloneの解消に合わせて閾値を下げます。

### バックエンド

- バックエンド変更時は push 前に `task verify`（静的解析、OpenAPI 契約検査と、使い捨てDBでのマイグレーション検証とテスト、CI と同じ内容）を実行します。
- 入力範囲が広い契約にはQuickTheoriesによるプロパティベーステストを使い、通常のテストと一緒に実行します。
- テストの検出力を確認するときは `task mutation-test` でPITを明示実行しますが、実行コストが高いため `task verify` には含めません。

### 可観測性

- `docker/otel-collector/config.yaml` を変更したときは `task otel-collector-check` を実行します。
  このタスクは許可していない属性を含むOTLPのログとFaroのpayload（例外、View、LCP、INP、CLS、trace）をCollectorに流し、匿名session IDで相関できる許可済みの値だけが残ることを確かめます。
  URL token、UUIDのsession ID、利用者情報、DOM情報、traceの`url.*`属性が残らないことも検査します。
  CSP違反の報告（Reporting APIの形式）も流し、文書のURL、query、fragmentが残らず、CSP以外の報告の型が捨てられることを確かめます。
  最初に、Collector の route allowlist が `routeTree.gen.ts` と一致することを確かめます。
- ブラウザの例外をローカルで送るには、`.env` に `FRONTEND_OTEL_ENABLED=true` を書いて `task compose-up` を実行し、`vp dev` を再起動して、Grafana で `{service_name="demo-web"}` を検索します（[ADR-068](docs/adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- 画面の API 呼び出しからバックエンドまでの trace は、次の手順で確かめます。
  1. 上と同じく `FRONTEND_OTEL_ENABLED=true` で `task compose-up` を実行し、バックエンドと `vp dev` を起動します。
  2. ログインして画面を開き、開発者ツールの console で `fetch("/api/missing")` を実行します。
  3. Grafana の Explore で Tempo を選び、`{resource.service.name="demo-web"}` を検索して trace を開きます。
     `demo-web` の `Browser request` の span の子に、`demo-api` の server span がつながります。
  4. SQL を発行する `/api/**` を呼ぶと、同じ trace に `jooq.query` の span（名前は `READ` など）が入ります（[ADR-070](docs/adr/ADR-070-record-sql-spans-with-jooq-execute-listener.md)）。
     main には SQL を発行する `/api/**` がまだありません。
- CSP違反の報告は、Collectorの`webhook_event` receiverが受け、Grafanaで`{service_name="demo-web"} | telemetry_signal="csp-violation"`を検索して見ます。
  ChromiumはHTTPの`localhost`では報告を送らないので、`vp dev`と`vp preview`で開いた画面からは届きません（[ADR-068](docs/adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- CIも同じタスクを、Collectorの設定、Composeファイル、Taskfileの変更時に実行します。

### 動的解析（DAST）

- `task scan-dast` は隔離した依存、backend、`vp preview` を起動し、OWASP ZAP で認証付きの passive scan を実行します。
  `task scan-dast-active` は同じ環境で active scan を実行します。
- CI は Pull Request で passive scan を、週 1 回の schedule と手動実行で active scan を実行します。
- 検出は SARIF で Security タブに送り、ジョブは失敗させません。
  失敗させるのは、起動、ログイン、CSRF の前提確認に失敗してスキャンが成立しない場合だけです（[ADR-056](docs/adr/ADR-056-run-authenticated-dast-with-zap-in-ci.md)）。

### ドキュメント

- `docs/`、`.kiro/steering/`、`Taskfile.yml`を編集したときは`task okf-check`を実行します。
  このタスクはOpen Knowledge Format v0.2バンドルの適合、docs内部のリンク切れ、孤立ドキュメント、steeringのfrontmatterとdocsとの境界を検査し、非index文書の250行超を責務混在の見直し合図として警告します。
  Taskfileと利用文書の同期候補も警告します。
- Lefthookは対象ファイルの変更を検出すると、pre-commitで同じ検査を実行します。

<p align="right">(<a href="#top">トップへ</a>)</p>

## 設計判断の記録（ADR）

重要な設計とアーキテクチャ上の判断は、Architecture Decision Record（ADR）として [`docs/adr/`](docs/adr/) に残します。

- 運用ルールの正文は [`docs/adr/conventions.md`](docs/adr/conventions.md) です（ADR を作る基準と作らないもの、記録先、採番、ライフサイクル）。
- 書式は [`docs/adr/adr-template.md`](docs/adr/adr-template.md) に準拠します。
- ADR の一覧は [`docs/adr/index.md`](docs/adr/index.md) を参照してください。
  `docs/adr/` はワークフロー外と横断の判断を残す場所です。

push 前には `task adr-check` が pre-push で走り、判断が絡む変更（依存、セキュリティ、DB、インフラ、ワークフロー、およびフロントエンド全体）に `docs/adr/` の更新が伴わないとき注意喚起します。
既定は非ブロッキングで、該当しない場合は `ADR_ACK=1 git push` で抑制できます。

<p align="right">(<a href="#top">トップへ</a>)</p>

## ドキュメント管理（iwe / OKF）

`docs/` 配下のドキュメントは [iwe](https://github.com/iwe-org/iwe) で管理し、[Open Knowledge Format](https://github.com/GoogleCloudPlatform/open-knowledge-format)（OKF）v0.2 バンドルとして構成しています。
各ドキュメントはフロントマターに `type`、`title`、`description`、`tags` を持ち、相互リンクでナレッジグラフを形成します。
これにより、ドキュメントの種別や関係を機械的に検索、検証でき、AI エージェントからも構造化された知識として参照できます。

- iwe は Markdown をナレッジグラフとして扱う CLI、LSP サーバー、MCP サーバーです。
  導入は [開発環境構築](docs/local-env-setup/setup.md) を参照してください。
- OKF適合、docs内部のリンク切れ、孤立ドキュメント、steeringのfrontmatterとdocsとの境界は`task okf-check`で検証し、非index文書の250行超は責務混在の見直し合図として警告します。
  同じタスクは、Taskfileの公開タスクを変更した場合にREADMEと関連docsの同期候補も警告します。
- 検証スキーマは `docs/.iwe/schemas/` に置き、`docs/index.md` を目次の起点としています。
  各領域の `docs/<領域>/index.md` は、どの文書をいつ読むかを示す入口です。
- AI エージェント向けの作成規約は [`.kiro/steering/documentation-authoring.md`](.kiro/steering/documentation-authoring.md)、docs の読み方は [`.kiro/steering/docs-navigation.md`](.kiro/steering/docs-navigation.md) にあります。
  この構成を採用した判断は [ADR-036](docs/adr/ADR-036-adopt-okf-for-docs-knowledge-bundle.md) と [ADR-038](docs/adr/ADR-038-route-steering-to-docs-knowledge.md) に記録しています。

<p align="right">(<a href="#top">トップへ</a>)</p>

## コントリビューションとリリース

変更手順、ブランチ運用、コミット規約は [`CONTRIBUTING.md`](CONTRIBUTING.md) を参照してください。

`main` の保護設定は [`docs/repository/branch-protection.md`](docs/repository/branch-protection.md)、版採番、CHANGELOG、タグの作成手順は [`docs/repository/release-management.md`](docs/repository/release-management.md) に定義しています。

リリース設定と版ファイルの一致は次のコマンドで確認できます。

```bash
task release-check
```

脆弱性の非公開報告方法は [`SECURITY.md`](SECURITY.md) を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>
