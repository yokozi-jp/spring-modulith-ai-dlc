# Lint・テストのリファレンス

静的解析、シークレットと脆弱性のスキャン、テストは、いずれも [`Makefile`](../Makefile) のターゲットとして実行できます（`make <ターゲット名>`）。
日常的な使い方（`make check` / `make verify` などの入口タスク）は [README](../README.md#開発コマンド) と [開発ワークフロー](dev-workflow.md) を参照してください。
このドキュメントは、個々のターゲットの内容と、Git フックと CI での自動実行の対応をまとめたリファレンスです。

Docker を使うターゲット（semgrep / trivy / actionlint / zizmor / hadolint / docker build --check / compose）は、Docker が無い環境ではスキップされます。

## バックエンド（Gradle）

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
changesetとjOOQ生成コードを更新する手順、本番の資格情報、デプロイ順序、DB切り戻しは[DBマイグレーションとjOOQコード生成](database-migrations.md)を参照してください。

`make test` はテスト専用スタック（`docker/compose-test.yml` の PostgreSQL 5433 / Redis 6380）を
`.env.test` で起動し、終了後にボリュームごと片付けます。
開発用スタック（`make compose-up` の 5432 / 6379）とポートを分けているため、`make be-run` で
バックエンドをホスト起動したまま `make test` を並行実行できます。

## シークレットスキャン（betterleaks）

| ターゲット              | 内容                                                  |
| ----------------------- | ----------------------------------------------------- |
| `make scan-secrets`     | ステージ済みの変更をスキャン（pre-commit 相当）       |
| `make scan-secrets-all` | リポジトリ全体（履歴含む）をスキャン（pre-push 相当） |

## 静的解析（Semgrep）

Semgrep OSS（コミュニティエディション）で静的解析を行います（`.kiro` / `aidlc` は対象外）。

| ターゲット          | 内容                                                    |
| ------------------- | ------------------------------------------------------- |
| `make lint-semgrep` | 静的解析（Semgrep OSS / Docker 実行、検出があれば失敗） |

## 静的解析・脆弱性スキャン（Snyk・任意）

Snyk は任意導入です。利用にはアカウント作成が必要で、本プロジェクトは free プランで運用しています。
`.snyk` にスキャン除外ポリシーを定義し、`.kiro` / `aidlc` / `.agents`（いずれも AI-DLC のフレームワークコードでプロダクションコードではない）を対象から除外しています。

## 脆弱性スキャン（Trivy）

依存関係の脆弱性を Trivy でスキャンします。backend は CycloneDX SBOM 経由、frontend は依存を解決してからスキャンします。

| ターゲット                 | 内容                                                       |
| -------------------------- | ---------------------------------------------------------- |
| `make scan-vulns`          | backend + frontend の脆弱性スキャン（Trivy / Docker 実行） |
| `make scan-vulns-backend`  | backend（Gradle）の脆弱性スキャン（SBOM 経由）             |
| `make scan-vulns-frontend` | frontend（pnpm）の脆弱性スキャン（依存解決後）             |

## GitHub Actions ワークフロー

| ターゲット                   | 内容                                     |
| ---------------------------- | ---------------------------------------- |
| `make lint-actions`          | ワークフローの Lint（actionlint）        |
| `make lint-actions-security` | ワークフローのセキュリティ解析（zizmor） |

## Docker / Compose

| ターゲット               | 内容                                                                  |
| ------------------------ | --------------------------------------------------------------------- |
| `make lint-docker`       | Dockerfile のベストプラクティス検査（hadolint）                       |
| `make lint-docker-check` | Dockerfile の Docker 公式チェック（docker build --check）             |
| `make lint-compose`      | Compose ファイルの構文・参照・変数展開の検証（docker compose config） |

## Markdown Lint（markdownlint-cli2）

Markdown ファイルの体裁を markdownlint-cli2 で検査します。除外設定は `.markdownlint-cli2.yaml` の `ignores` に従います（`.kiro` 配下は steering のみ対象）。

| ターゲット         | 内容                                              |
| ------------------ | ------------------------------------------------- |
| `make lint-md`     | Markdown の Lint（検出があれば失敗）              |
| `make lint-md-fix` | Markdown の Lint 自動修正（安全に直せる項目のみ） |

## 自動実行（Git フック / CI）

- **Git フック（Lefthook, [`lefthook.yml`](../lefthook.yml)）**
  - commit-msg: commitlint（コミットメッセージを Conventional Commits 規約で検証）
  - pre-commit: betterleaks（ステージ済み）、hadolint / docker build --check（Dockerfile 変更時）、compose config（Compose 変更時）、markdownlint（Markdown 変更時）
  - pre-push: betterleaks（全履歴）、be-lint（Spotless + PMD + SpotBugs）/ be-test（`make test`）（backend 変更時）、actionlint / zizmor（ワークフロー変更時）
- **CI（GitHub Actions, [`.github/workflows/`](../.github/workflows/)）**
  - `backend-ci.yml`（backend の Lint（Spotless + PMD + SpotBugs）とテスト・カバレッジ）、`betterleaks.yml`（シークレットスキャン）、`semgrep.yml`（静的解析 / SARIF アップロード）、`trivy.yml`（脆弱性スキャン / SARIF アップロード）、`actionlint.yml` / `zizmor.yml`（ワークフロー）、`hadolint.yml`（Dockerfile Lint、docker build --check、backend イメージのビルド・起動・ヘルスチェック）、`compose-config.yml`（Compose）、`markdownlint.yml`（Markdown）
  - `semgrep.yml` / `trivy.yml` の検出結果は GitHub Code Scanning（Security タブ）に SARIF 形式でアップロードされます。
