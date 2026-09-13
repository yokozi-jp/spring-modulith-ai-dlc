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
7. [設計判断の記録（ADR）](#設計判断の記録adr)

## プロジェクトについて

**Spring Modulith** を用いた**モジュラーモノリス**の土台となるプロジェクトです。
単一のデプロイ単位の内側を業務モジュールへ分割し、モジュール間の境界と依存を Spring Modulith で検証しながら開発することを狙いとしています。
開発は [AI-DLC（AI-Driven Development Life Cycle）](https://github.com/awslabs/aidlc-workflows)に沿って進めます。

現時点で整備済みなのは、業務モジュールを載せる前の基盤部分です。

- **認証と認可**：Keycloak を認可サーバとした OIDC（OAuth2 Client）と、Redis による分散セッション
- **データアクセス**：Liquibase によるDBマイグレーションと、スキーマから生成する jOOQ コード
- **可観測性**：OpenTelemetry による計装と、Grafana OpenTelemetry LGTM への集約
- **品質ゲート**：静的解析、シークレットと脆弱性のスキャン、使い捨てDBでのテストを Git フックと CI で強制

業務ドメインのモジュールはこれから追加していきます。

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
│   ├── adr/              # Architecture Decision Records（設計判断の記録）
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

- 導入手順は [AI-DLC セットアップ](docs/aidlc-setup/setup.md) を参照してください。
- 使い方は [AI-DLC の公式ドキュメント](https://github.com/awslabs/aidlc-workflows/tree/main/docs) を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## 開発コマンド

ビルド、テスト、SBOM 生成などの各種コマンドは [`Makefile`](Makefile) にまとめています。

```bash
make <ターゲット名>
```

引数なしの `make`（または `make help`）で、カテゴリ別のターゲット一覧を表示します。

### Quick Start

初回は次の順で環境を立ち上げます。

```bash
cp .env.example .env       # 環境変数を用意し、パスワードを変更する
make compose-up            # PostgreSQL / Keycloak / Redis / Grafana を起動
make be-migrate            # 初回はマイグレーションを明示実行する
make dev                   # 依存起動＋バックエンドを起動
```

バックエンドは <http://localhost:18080>、Keycloak は <http://localhost:8080>、Grafana は <http://localhost:3000> で公開されます。

日々の開発で使う入口タスクは次の三つです。

- **`make dev`**：依存サービスを起動してバックエンドを起動（日々の開発の入口）。
- **`make check`**：素早いローカル確認（バックエンドの静的解析）。
- **`make verify`**：push 前の総合ゲート（静的解析と、使い捨てDBでのマイグレーション検証とテスト。CI と同じ内容）。

Docker Compose の操作（サービスの起動、停止、状態確認、Keycloak の realm 再投入）や、「いつ、どのコマンドを、どの順で使うか」のシナリオ別の手順は [開発ワークフロー](docs/dev-workflow.md) を参照してください。

<p align="right">(<a href="#top">トップへ</a>)</p>

## Lint・テスト

静的解析、シークレットと脆弱性のスキャン、テストは、いずれも [`Makefile`](Makefile) のターゲットとして実行できます（`make <ターゲット名>`）。
日常的には push 前に `make verify`（静的解析と、使い捨てDBでのマイグレーション検証とテスト、CI と同じ内容）を回せば足ります。

各ターゲットの一覧と内容、Git フックと CI での自動実行の対応は [Lint・テストのリファレンス](docs/lint-and-test.md) にまとめています。

<p align="right">(<a href="#top">トップへ</a>)</p>

## 設計判断の記録（ADR）

重要な設計・アーキテクチャ上の判断は、Architecture Decision Record（ADR）として [`docs/adr/`](docs/adr/) に残します。

- 規約は [`.kiro/steering/adr-decision-record.md`](.kiro/steering/adr-decision-record.md) に定義しています（ADR を作る/作らない基準、記録先、ライフサイクル）。
- 書式は AI-DLC 同梱テンプレート（`.kiro/knowledge/aidlc-architect-agent/adr-template.md`）に準拠します。
- ADR の一覧は [`docs/adr/index.md`](docs/adr/index.md) を参照してください。
- インテント固有の設計判断は、AI-DLC が inception 実行時に各インテントの record dir（`<record>/inception/domain-design/decisions.md`）へ生成します。`docs/adr/` はワークフロー外・横断の判断を残す場所です。

push 前には `make adr-check` が pre-push で走り、判断が絡む変更（依存・セキュリティ・DB・インフラ・ワークフロー、およびフロントエンド全体）に `docs/adr/` の更新が伴わないとき注意喚起します。
既定は非ブロッキングで、該当しない場合は `ADR_ACK=1 git push` で抑制できます。

<p align="right">(<a href="#top">トップへ</a>)</p>
