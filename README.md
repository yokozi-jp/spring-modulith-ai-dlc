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

| 言語・フレームワーク    | バージョン |
| ----------------------- | ---------- |
| Java (Amazon Corretto)  | 25         |
| Spring Boot             | 4.1.1      |
| Spring Modulith         | 2.1.0      |
| TypeScript              | 7.0.x      |
| VitePlus                | latest     |
| pnpm                    | 11.21.0    |
| PostgreSQL              | 18         |
| Redis                   | 7.x        |
| Go (betterleaks 実行用) | 1.27.x     |
| Bun (AI-DLC ランタイム) | 1.3.14     |

その他のパッケージのバージョンは `backend/build.gradle` と `frontend/package.json` を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## ディレクトリ構成

```text
.
├── backend/          # Spring Boot アプリケーション
├── frontend/         # VitePlus + TypeScript フロントエンド（pnpm）
├── docker/           # Docker 関連ファイル
├── infrastructure/   # インフラ定義
├── docs/             # ドキュメント
│   ├── local-env-setup/  # 開発環境構築手順・スクリプト
│   └── aidlc-setup/      # AI-DLC セットアップ手順
├── aidlc/            # AI-DLC ワークスペース（自動生成）
├── .github/          # GitHub 設定
│   ├── workflows/    # GitHub Actions（Lint・シークレットスキャン等）
│   └── dependabot.yml # 依存関係の自動更新設定
├── .kiro/            # Kiro 設定
├── .vscode/          # VSCode 設定
├── .betterleaks.toml # betterleaks（シークレットスキャナ）設定
├── .hadolint.yaml    # hadolint（Dockerfile リンタ）設定
├── .markdownlint-cli2.yaml # markdownlint-cli2（Markdown リンタ）設定
├── .env.example      # 環境変数のサンプル
├── lefthook.yml      # Git フック定義（Lefthook）
├── commitlint.config.mjs # commitlint 設定（Conventional Commits 検証）
├── package.json      # Lefthook・commitlint 導入用（ルート）
└── Makefile          # 開発コマンド定義
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

<p align="right">(<a href="#top">トップへ</a>)</p>

## Lint・テスト

各種チェックは [`Makefile`](Makefile) のターゲットとして実行できます（`make <ターゲット名>`）。
Docker を使うターゲット（semgrep / trivy / actionlint / zizmor / hadolint / docker build --check / compose）は、Docker が無い環境ではスキップされます。

### バックエンド（Gradle）

| ターゲット       | 内容                                           |
| ---------------- | ---------------------------------------------- |
| `make be-format` | コードフォーマット適用（Spotless）             |
| `make be-lint`   | 静的解析（PMD + SpotBugs + Spotless チェック） |
| `make be-test`   | テスト実行＆カバレッジ検証                     |
| `make be-sbom`   | SBOM 生成（CycloneDX 形式）                    |

### シークレットスキャン（betterleaks）

| ターゲット              | 内容                                                  |
| ----------------------- | ----------------------------------------------------- |
| `make scan-secrets`     | ステージ済みの変更をスキャン（pre-commit 相当）       |
| `make scan-secrets-all` | リポジトリ全体（履歴含む）をスキャン（pre-push 相当） |

### 静的解析（Semgrep）

Semgrep OSS（コミュニティエディション）で静的解析を行います（`.kiro` / `aidlc` は対象外）。

| ターゲット           | 内容                                                     |
| -------------------- | -------------------------------------------------------- |
| `make lint-semgrep`  | 静的解析（Semgrep OSS / Docker 実行、検出があれば失敗）  |

### 脆弱性スキャン（Trivy）

依存関係の脆弱性を Trivy でスキャンします。backend は CycloneDX SBOM 経由、frontend は依存を解決してからスキャンします。

| ターゲット                  | 内容                                                          |
| --------------------------- | ------------------------------------------------------------- |
| `make scan-vulns`           | backend + frontend の脆弱性スキャン（Trivy / Docker 実行）    |
| `make scan-vulns-backend`   | backend（Gradle）の脆弱性スキャン（SBOM 経由）                |
| `make scan-vulns-frontend`  | frontend（pnpm）の脆弱性スキャン（依存解決後）                |

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

| ターゲット                | 内容                                             |
| ------------------------- | ------------------------------------------------ |
| `make lint-md`            | Markdown の Lint（検出があれば失敗）             |
| `make lint-md-fix`        | Markdown の Lint 自動修正（安全に直せる項目のみ）|

### 自動実行（Git フック / CI）

- **Git フック（Lefthook, [`lefthook.yml`](lefthook.yml)）**
  - commit-msg: commitlint（コミットメッセージを Conventional Commits 規約で検証）
  - pre-commit: betterleaks（ステージ済み）、hadolint / docker build --check（Dockerfile 変更時）、compose config（Compose 変更時）、markdownlint（Markdown 変更時）
  - pre-push: betterleaks（全履歴）、actionlint / zizmor（ワークフロー変更時）
- **CI（GitHub Actions, [`.github/workflows/`](.github/workflows/)）**
  - `betterleaks.yml`（シークレットスキャン）、`semgrep.yml`（静的解析 / SARIF アップロード）、`trivy.yml`（脆弱性スキャン / SARIF アップロード）、`actionlint.yml` / `zizmor.yml`（ワークフロー）、`hadolint.yml`（Dockerfile Lint、docker build --check、backend イメージのビルド、起動、ヘルスチェック）、`compose-config.yml`（Compose）、`markdownlint.yml`（Markdown）
  - `semgrep.yml` / `trivy.yml` の検出結果は GitHub Code Scanning（Security タブ）に SARIF 形式でアップロードされます。

<p align="right">(<a href="#top">トップへ</a>)</p>

## トラブルシューティング

（随時追記）

<p align="right">(<a href="#top">トップへ</a>)</p>
