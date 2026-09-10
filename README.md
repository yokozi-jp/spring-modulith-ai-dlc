<!-- markdownlint-disable-next-line MD041 -->
<div id="top"></div>

# spring-modulith-ai-dlc

## 使用技術一覧

<p style="display: inline">
  <!-- バックエンド -->
  <img alt="Java 25" src="https://img.shields.io/badge/-Java%2025-007396.svg?logo=openjdk&style=for-the-badge">
  <img alt="Spring Boot 4.1" src="https://img.shields.io/badge/-Spring%20Boot%204.1-6DB33F.svg?logo=spring-boot&style=for-the-badge">
  <img alt="Spring Modulith" src="https://img.shields.io/badge/-Spring%20Modulith-6DB33F.svg?logo=spring&style=for-the-badge">
  <!-- フロントエンド -->
  <img alt="TypeScript" src="https://img.shields.io/badge/-TypeScript-3178C6.svg?logo=typescript&style=for-the-badge&logoColor=white">
  <img alt="VitePlus" src="https://img.shields.io/badge/-VitePlus-646CFF.svg?logo=vite&style=for-the-badge&logoColor=white">
  <img alt="pnpm" src="https://img.shields.io/badge/-pnpm-F69220.svg?logo=pnpm&style=for-the-badge&logoColor=white">
  <!-- ミドルウェア -->
  <img alt="PostgreSQL" src="https://img.shields.io/badge/-PostgreSQL-4169E1.svg?logo=postgresql&style=for-the-badge&logoColor=white">
  <img alt="Redis" src="https://img.shields.io/badge/-Redis-DC382D.svg?logo=redis&style=for-the-badge&logoColor=white">
  <img alt="Keycloak" src="https://img.shields.io/badge/-Keycloak-4D4D4D.svg?logo=keycloak&style=for-the-badge&logoColor=white">
  <img alt="Grafana OpenTelemetry LGTM" src="https://img.shields.io/badge/-Grafana%20OTel%20LGTM-F46800.svg?logo=grafana&style=for-the-badge&logoColor=white">
  <!-- インフラ -->
  <img alt="Docker" src="https://img.shields.io/badge/-Docker-1488C6.svg?logo=docker&style=for-the-badge">
  <img alt="Amazon AWS" src="https://img.shields.io/badge/-Amazon%20AWS-232F3E.svg?logo=amazon-aws&style=for-the-badge">
  <img alt="GitHub Actions" src="https://img.shields.io/badge/-GitHub%20Actions-2088FF.svg?logo=github-actions&style=for-the-badge&logoColor=white">
</p>

## 目次

1. [プロジェクトについて](#プロジェクトについて)
2. [環境](#環境)
3. [ディレクトリ構成](#ディレクトリ構成)
4. [開発環境構築](#開発環境構築)
5. [開発コマンド](#開発コマンド)
6. [Lint・テスト](#lintテスト)
7. [トラブルシューティング](#トラブルシューティング)

## プロジェクトについて

Spring Modulith を用いたモジュラーモノリスアーキテクチャのサンプルプロジェクト。

## 環境

| 言語・フレームワーク       | バージョン |
| -------------------------- | ---------- |
| Java (Amazon Corretto)     | 25         |
| Spring Boot                | 4.1.1      |
| Spring Modulith            | 2.1.1      |
| TypeScript                 | 7.0.x      |
| VitePlus                   | latest     |
| pnpm                       | 11.21.0    |
| PostgreSQL                 | 18         |
| Redis                      | 7.x        |
| Keycloak                   | 26.7.3     |
| Grafana OpenTelemetry LGTM | 0.32.1     |
| Go (betterleaks 実行用)    | 1.27.x     |
| Bun (AI-DLC ランタイム)    | 1.3.14     |

その他のパッケージのバージョンは `backend/build.gradle` と `frontend/package.json` を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## ディレクトリ構成

```text
.
├── .agents/          # スキル定義（.agents/skills）
├── .github/          # GitHub 設定
│   ├── workflows/    # GitHub Actions（Lint・シークレットスキャン等）
│   └── dependabot.yml # 依存関係の自動更新設定
├── .kiro/            # Kiro 設定
├── .vscode/          # VSCode 設定
├── aidlc/            # AI-DLC ワークスペース（自動生成）
├── backend/          # Spring Boot アプリケーション
├── docker/           # Docker 関連ファイル
│   ├── initdb/       # PostgreSQL 初期化スクリプト（スキーマ作成）
│   └── keycloak/     # Keycloak realm 定義（起動時インポート）
├── docs/             # ドキュメント
│   ├── aidlc-setup/      # AI-DLC セットアップ手順
│   └── local-env-setup/  # 開発環境構築手順・スクリプト
├── frontend/         # VitePlus + TypeScript フロントエンド（pnpm）
├── infrastructure/   # インフラ定義
├── .betterleaks.toml # betterleaks（シークレットスキャナ）設定
├── .env.example      # 環境変数のサンプル
├── .env.test         # テスト用の環境変数（非機密ダミー）
├── .gitignore        # Git 追跡除外設定
├── .hadolint.yaml    # hadolint（Dockerfile リンタ）設定
├── .markdownlint-cli2.yaml # markdownlint-cli2（Markdown リンタ）設定
├── .snyk             # Snyk のスキャン除外ポリシー（プロダクションコード以外を除外）
├── AGENTS.md         # AI-DLC / エージェント向けプロジェクト説明
├── commitlint.config.mjs # commitlint 設定（Conventional Commits 検証）
├── lefthook.yml      # Git フック定義（Lefthook）
├── LICENSE           # ライセンス
├── Makefile          # 開発コマンド定義
├── package.json      # Lefthook・commitlint 導入用（ルート）
└── skills-lock.json  # スキルのバージョン固定（lock）
```

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発環境構築

[開発環境構築ガイド](docs/local-env-setup/setup.md) を参照してください。

### AI-DLC のセットアップ

[AI-DLC セットアップ](docs/aidlc-setup/setup.md) を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発コマンド

ビルド、テスト、SBOM 生成などの各種コマンドは [`Makefile`](Makefile) にまとめています。

```bash
make <ターゲット名>
```

### Docker Compose

ルートのサンプルを `.env` へコピーし、パスワードを変更します。

```bash
cp .env.example .env
```

通常は PostgreSQL、Keycloak、Redis、Grafana OpenTelemetry LGTM だけを起動します。

```bash
make compose-up
```

バックエンドの初回起動前とchangeset追加後に、マイグレーションを明示実行します。

```bash
make be-migrate
```

バックエンドはルートの `.env` を読み込み、ホスト上で起動します。

```bash
make be-run
```

バックエンドは <http://localhost:18080>、Keycloak は <http://localhost:8080>、Grafana は <http://localhost:3000> で公開されます。

バックエンドもコンテナで確認するときだけ `backend` profile を有効にします。

```bash
make compose-up-backend
```

サービスの状態確認と停止には次のターゲットを使用します。

```bash
make compose-ps
make compose-down
```

Keycloak の OIDC discovery、issuer、PKCE S256 対応を確認します。

```bash
make oidc-check
```

Keycloak のログだけを追跡する場合は次のターゲットを使用します。

```bash
make keycloak-logs
```

`realm.json` を変更した場合、既存 realm は起動時インポートで上書きされません。
Keycloak のローカルデータだけを削除して realm を再投入するには、次のターゲットを使用します。

```bash
make keycloak-reimport
```

`make keycloak-reimport` は PostgreSQL、Redis、Grafana のデータを保持します。
全サービスのデータも削除して初期化する場合だけ `make compose-reset` を使用します。

<p align="right">(<a href="#top">トップへ</a>)</p>

## Lint・テスト

各種チェックは [`Makefile`](Makefile) のターゲットとして実行できます（`make <ターゲット名>`）。
Docker を使うターゲット（semgrep / trivy / actionlint / zizmor / hadolint / docker build --check / compose）は、Docker が無い環境ではスキップされます。

### バックエンド（Gradle）

| ターゲット                  | 内容                                                           |
| --------------------------- | -------------------------------------------------------------- |
| `make be-format`            | コードフォーマット適用（Spotless）                             |
| `make be-lint`              | 静的解析（PMD + SpotBugs + Spotless チェック）                 |
| `make be-migrate`           | 現在のスキーマタグまでマイグレーション                         |
| `make be-release-migrate`   | 本番向けマイグレーション（実行時のタグ入力は不要）             |
| `make be-schema-tag-check`  | 現在のスキーマタグがDBに存在することを確認                     |
| `make be-verify-migrations` | 使い捨てDBで適用、rollback、再適用、タグを検証                 |
| `make be-rollback-check`    | 切り戻し対象タグの存在を確認                                   |
| `make be-rollback-preview`  | 指定タグまでの切り戻しSQLを生成（DB変更なし）                  |
| `make be-rollback`          | 明示確認付きで指定タグまで切り戻し                             |
| `make be-generate-jooq`     | 現在のDBからjOOQコードを生成                                   |
| `make be-refresh-jooq`      | マイグレーション後の最新DBからjOOQコードを生成                 |
| `make test`                 | 隔離DBでrollback検証後にテストして片付ける                     |
| `make be-test`              | 明示マイグレーションとテストを実行（依存起動済みのCI部品用）   |
| `make be-sbom`              | SBOM生成（CycloneDX形式）                                      |

アプリケーション起動時のLiquibase自動実行は無効です。
jOOQコード生成には公式`org.jooq.jooq-codegen-gradle`プラグイン3.21.7を使用し、PostgreSQLの絶対時刻を`Instant`へマッピングします。
生成コードはchangesetと同じ変更としてGit管理し、本番のDBマイグレーションとアプリケーションデプロイではjOOQコード生成を実行しません。
DBスキーマタグはchangelog内の`tagDatabase` changesetで管理し、現在タグは`backend/gradle.properties`から自動選択します。
ローカルの初回起動時とchangeset追加後は`make be-migrate`を実行してください。
本番ではタグを手入力せず、`make be-release-migrate`でリポジトリに固定されたスキーマタグまで適用します。
changesetとjOOQ生成コードを更新する手順、本番の資格情報、デプロイ順序、DB切り戻しは[DBマイグレーションとjOOQコード生成](docs/database-migrations.md)を参照してください。

`make test` はテスト専用スタック（`docker/compose-test.yml` の PostgreSQL 5433 / Redis 6380）を
`.env.test` で起動し、終了後にボリュームごと片付けます。
開発用スタック（`make compose-up` の 5432 / 6379）とポートを分けているため、`make be-run` で
バックエンドをホスト起動したまま `make test` を並行実行できます。

### シークレットスキャン（betterleaks）

| ターゲット              | 内容                                                  |
| ----------------------- | ----------------------------------------------------- |
| `make scan-secrets`     | ステージ済みの変更をスキャン（pre-commit 相当）       |
| `make scan-secrets-all` | リポジトリ全体（履歴含む）をスキャン（pre-push 相当） |

### 静的解析（Semgrep）

Semgrep OSS（コミュニティエディション）で静的解析を行います（`.kiro` / `aidlc` は対象外）。

| ターゲット          | 内容                                                    |
| ------------------- | ------------------------------------------------------- |
| `make lint-semgrep` | 静的解析（Semgrep OSS / Docker 実行、検出があれば失敗） |

### 静的解析・脆弱性スキャン（Snyk・任意）

Snyk は任意導入です。利用にはアカウント作成が必要で、本プロジェクトは free プランで運用しています。
`.snyk` にスキャン除外ポリシーを定義し、`.kiro` / `aidlc` / `.agents`（いずれも AI-DLC のフレームワークコードでプロダクションコードではない）を対象から除外しています。

### 脆弱性スキャン（Trivy）

依存関係の脆弱性を Trivy でスキャンします。backend は CycloneDX SBOM 経由、frontend は依存を解決してからスキャンします。

| ターゲット                 | 内容                                                       |
| -------------------------- | ---------------------------------------------------------- |
| `make scan-vulns`          | backend + frontend の脆弱性スキャン（Trivy / Docker 実行） |
| `make scan-vulns-backend`  | backend（Gradle）の脆弱性スキャン（SBOM 経由）             |
| `make scan-vulns-frontend` | frontend（pnpm）の脆弱性スキャン（依存解決後）             |

### GitHub Actions ワークフロー

| ターゲット                   | 内容                                     |
| ---------------------------- | ---------------------------------------- |
| `make lint-actions`          | ワークフローの Lint（actionlint）        |
| `make lint-actions-security` | ワークフローのセキュリティ解析（zizmor） |

### Docker / Compose

| ターゲット               | 内容                                                                  |
| ------------------------ | --------------------------------------------------------------------- |
| `make lint-docker`       | Dockerfile のベストプラクティス検査（hadolint）                       |
| `make lint-docker-check` | Dockerfile の Docker 公式チェック（docker build --check）             |
| `make lint-compose`      | Compose ファイルの構文・参照・変数展開の検証（docker compose config） |

### Markdown Lint（markdownlint-cli2）

Markdown ファイルの体裁を markdownlint-cli2 で検査します。除外設定は `.markdownlint-cli2.yaml` の `ignores` に従います（`.kiro` 配下は steering のみ対象）。

| ターゲット         | 内容                                              |
| ------------------ | ------------------------------------------------- |
| `make lint-md`     | Markdown の Lint（検出があれば失敗）              |
| `make lint-md-fix` | Markdown の Lint 自動修正（安全に直せる項目のみ） |

### 自動実行（Git フック / CI）

- **Git フック（Lefthook, [`lefthook.yml`](lefthook.yml)）**
  - commit-msg: commitlint（コミットメッセージを Conventional Commits 規約で検証）
  - pre-commit: betterleaks（ステージ済み）、hadolint / docker build --check（Dockerfile 変更時）、compose config（Compose 変更時）、markdownlint（Markdown 変更時）
  - pre-push: betterleaks（全履歴）、be-lint（Spotless + PMD + SpotBugs）/ be-test（`make test`）（backend 変更時）、actionlint / zizmor（ワークフロー変更時）
- **CI（GitHub Actions, [`.github/workflows/`](.github/workflows/)）**
  - `backend-ci.yml`（backend の Lint（Spotless + PMD + SpotBugs）とテスト・カバレッジ）、`betterleaks.yml`（シークレットスキャン）、`semgrep.yml`（静的解析 / SARIF アップロード）、`trivy.yml`（脆弱性スキャン / SARIF アップロード）、`actionlint.yml` / `zizmor.yml`（ワークフロー）、`hadolint.yml`（Dockerfile Lint、docker build --check、backend イメージのビルド・起動・ヘルスチェック）、`compose-config.yml`（Compose）、`markdownlint.yml`（Markdown）
  - `semgrep.yml` / `trivy.yml` の検出結果は GitHub Code Scanning（Security タブ）に SARIF 形式でアップロードされます。

<p align="right">(<a href="#top">トップへ</a>)</p>

## トラブルシューティング

（随時追記）

<p align="right">(<a href="#top">トップへ</a>)</p>
