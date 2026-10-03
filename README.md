<!-- markdownlint-disable-next-line MD041 -->
<div id="top"></div>

# spring-modulith-ai-dlc

## 目次

1. [プロジェクトについて](#プロジェクトについて)
2. [環境](#環境)
3. [ディレクトリ構成](#ディレクトリ構成)
4. [開発環境構築](#開発環境構築)
5. [開発コマンド](#開発コマンド)
6. [Lint・テスト](#lintテスト)
7. [設計判断の記録（ADR）](#設計判断の記録adr)
8. [ドキュメント管理（iwe / OKF）](#ドキュメント管理iwe--okf)

## プロジェクトについて

**Spring Modulith** を用いた**モジュラーモノリス**の土台となるプロジェクトです。
単一のデプロイ単位の内側を業務モジュールへ分割し、モジュール間の境界と依存を Spring Modulith で検証しながら開発することを狙いとしています。

現時点で整備済みなのは、業務モジュールを載せる前の基盤部分です。

- **認証と認可**：Keycloak を認可サーバとした OIDC（OAuth2 Client）と、Redis による分散セッション
- **データアクセス**：Liquibase によるDBマイグレーションと、スキーマから生成する jOOQ コード
- **可観測性**：OpenTelemetry による計装と、OpenTelemetry Collector でログの属性を絞ってからの Grafana OpenTelemetry LGTM への集約（ログ相関、PII、保持は[可観測性データの規約](docs/observability/conventions.md)を参照）
- **品質ゲート**：静的解析、シークレットと脆弱性のスキャン、使い捨てDBでのテストを Git フックと CI で強制

業務ドメインのモジュールはこれから追加していきます。

## 環境

| 言語・フレームワーク       | バージョン |
| -------------------------- | ---------- |
| Java (Amazon Corretto)     | 25         |
| Spring Boot                | 4.1.1      |
| Spring Modulith            | 2.1.1      |
| TypeScript                 | 7.0.x      |
| Node.js                    | 24.21.0    |
| VitePlus                   | 0.3.3      |
| pnpm                       | 11.21.0    |
| PostgreSQL                 | 18         |
| Redis                      | 7.x        |
| Keycloak                   | 26.7.3     |
| Grafana OpenTelemetry LGTM | 0.32.1     |
| OpenTelemetry Collector    | 0.161.0    |
| Go (betterleaks 実行用)    | 1.27.x     |
| Task                       | 3.53.1     |

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
│   ├── container/        # コンテナ（Dockerfile、Compose）の規約
│   ├── database/         # データベース（マイグレーション、jOOQ、接続）の規約
│   ├── datetime/         # 日時とタイムゾーンの規約
│   ├── frontend/         # フロントエンドの規約
│   ├── knowledge/        # ナレッジ管理（docs と steering の役割分担）
│   ├── local-env-setup/  # 開発環境構築手順・スクリプト
│   ├── tooling/          # 開発ツール（Taskfile、フック、CI、Lint）の規約
│   ├── writing/          # 日本語の技術文書の書き方
│   └── index.md          # docs の入口
├── frontend/         # VitePlus + TypeScript フロントエンド（pnpm）
├── infrastructure/   # インフラ定義（未整備）
├── .betterleaks.toml # betterleaks（シークレットスキャナ）設定
├── .editorconfig     # エディタ共通設定
├── .env.example      # 環境変数のサンプル
├── .env.test         # テスト用の環境変数（非機密ダミー）
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

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発コマンド

ビルド、テスト、SBOM 生成などの各種コマンドは [`Taskfile.yml`](Taskfile.yml) にまとめています。

```bash
task <タスク名>
```

引数なしの`task`、`task help`、または`task --list`で、公開タスクの一覧を表示します。

### Quick Start

初回は次の順で環境を立ち上げます。

```bash
cp .env.example .env       # 環境変数を用意し、パスワードを変更する
task compose-up            # PostgreSQL / Keycloak / Redis / Collector / Grafana を起動
task be-migrate            # 初回はマイグレーションを明示実行する
task dev                   # 依存起動＋バックエンドを起動
# 別のターミナルで
cd frontend && vp dev      # SPAを起動し、APIとOIDCを同一オリジンでproxy
```

ブラウザは <http://localhost:5173> を開きます。
バックエンドは <http://localhost:18080>、Keycloak は <http://localhost:8080>、Grafana は <http://localhost:3000> で公開されます。

日々の開発で使う入口タスクは次の五つです。

- **`task dev`**：依存サービスを起動してバックエンドを起動（日々の開発の入口）。
- **`task check`**：素早いローカル確認（バックエンドの静的解析）。
- **`task fe-verify`**：フロントエンドの静的解析、未使用コード検査、テスト、本番ビルド。
- **`task lint-duplicates`**：フロントエンドとバックエンドの手書きコードの重複検査。
- **`task verify`**：push 前のバックエンド総合ゲート（静的解析、OpenAPI 契約検査と、使い捨てDBでのマイグレーション検証とテスト、CI と同じ内容）。

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
- `task fe-doctor`は手動のReact診断とFrontend CIで実行し、15分以内に完了しない場合は検査を失敗させます。
- React Doctorのwarningとerrorはどちらもblockingとし、Frontend CIを停止します。
- LefthookはFrontend変更を検出すると、pre-commitで `task fe-check`、pre-pushで `task fe-test-build` を実行します。
- Frontend CIはPull Requestと `main` へのpushで `task fe-verify`、`task fe-route-tree-check`、`task fe-doctor` を実行し、Knip、生成済みroute treeの一致、React診断、全体branch coverage 85%を強制します。
- coverageレポートは `task fe-coverage` で確認でき、CIでは14日間artifactとして保存します。

### 重複コード検査

- `task lint-duplicates` はTanStack RouterとjOOQの生成コードを除いたFrontendとBackendの手書きソースをjscpdで検査します。
- 既存の重複行率3.19%を基準に3.2%を上限とし、既存cloneの解消に合わせて閾値を下げます。

### バックエンド

- バックエンド変更時は push 前に `task verify`（静的解析、OpenAPI 契約検査と、使い捨てDBでのマイグレーション検証とテスト、CI と同じ内容）を実行します。
- 入力範囲が広い契約にはQuickTheoriesによるプロパティベーステストを使い、通常のテストと一緒に実行します。
- テストの検出力を確認するときは `task mutation-test` でPITを明示実行しますが、実行コストが高いため `task verify` には含めません。

### 可観測性

- `docker/otel-collector/config.yaml` を変更したときは `task otel-collector-check` を実行します。
  このタスクは許可していない属性を含むOTLPのログをCollectorに流し、その属性が除かれ、許可した属性が残ることを確かめます。
- CIも同じタスクを、Collectorの設定、Composeファイル、Taskfileの変更時に実行します。

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
