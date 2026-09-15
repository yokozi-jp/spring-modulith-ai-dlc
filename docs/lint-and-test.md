# Lint・テストのリファレンス

静的解析、シークレットと脆弱性のスキャン、テストは、いずれも [`Taskfile.yml`](../Taskfile.yml) のタスクとして実行できます（`task <タスク名>`）。
日常的な使い方（`task check` / `task verify` などの入口タスク）は [README](../README.md#開発コマンド) と [開発ワークフロー](dev-workflow.md) を参照してください。
このドキュメントは、個々のタスクの内容と、Git フックと CI での自動実行の対応をまとめたリファレンスです。

Docker を使うタスク（semgrep / trivy / actionlint / zizmor / hadolint / docker build --check / compose）は、Docker が無い環境ではスキップされます。

## バックエンド（Gradle）

| 実行タスク                  | 内容                                                           |
| --------------------------- | -------------------------------------------------------------- |
| `task be-format`            | コードフォーマット適用（Spotless）                             |
| `task be-lint`              | 静的解析（PMD + SpotBugs + Spotless チェック）                 |
| `task be-openapi-lint`      | 生成したOpenAPI 3.1契約をSpectralで検査（依存起動済み）        |
| `task be-migrate`           | 現在のスキーマタグまでマイグレーション                         |
| `task be-release-migrate`   | 本番向けマイグレーション（実行時のタグ入力は不要）             |
| `task be-schema-tag-check`  | 現在のスキーマタグがDBに存在することを確認                     |
| `task be-verify-migrations` | 使い捨てDBで適用、rollback、再適用、タグを検証                 |
| `task be-rollback-check`    | 切り戻し対象タグの存在を確認                                   |
| `task be-rollback-preview`  | 指定タグまでの切り戻しSQLを生成（DB変更なし）                  |
| `task be-rollback`          | 明示確認付きで指定タグまで切り戻し                             |
| `task be-generate-jooq`     | 現在のDBからjOOQコードを生成                                   |
| `task be-refresh-jooq`      | マイグレーション後の最新DBからjOOQコードを生成                 |
| `task test`                 | 隔離DBでrollback検証後にテストして片付ける                     |
| `task mutation-test`        | 隔離DBでPITミューテーションテストを実行して片付ける            |
| `task be-test`              | 明示マイグレーションとテストを実行（依存起動済みのCI部品用）   |
| `task be-mutation-test`     | PITを実行（依存起動済みのCI部品用）                            |
| `task be-sbom`              | SBOM生成（CycloneDX形式）                                      |

アプリケーション起動時のLiquibase自動実行は無効です。
jOOQコード生成には公式`org.jooq.jooq-codegen-gradle`プラグイン3.21.7を使用し、PostgreSQLの絶対時刻を`Instant`へマッピングします。
生成コードはchangesetと同じ変更としてGit管理し、本番のDBマイグレーションとアプリケーションデプロイではjOOQコード生成を実行しません。
DBスキーマタグはchangelog内の`tagDatabase` changesetで管理し、現在タグは`backend/gradle.properties`から自動選択します。
ローカルの初回起動時とchangeset追加後は`task be-migrate`を実行してください。
本番ではタグを手入力せず、`task be-release-migrate`でリポジトリに固定されたスキーマタグまで適用します。
changesetとjOOQ生成コードを更新する手順、本番の資格情報、デプロイ順序、DB切り戻しは[DBマイグレーションとjOOQコード生成](database-migrations.md)を参照してください。

`task test` はテスト専用スタック（`docker/compose-test.yml` の PostgreSQL 5433 / Redis 6380）を
`.env.test` で起動し、マイグレーション、テスト、生成したOpenAPI 3.1契約のSpectral検査を実行してから、ボリュームごと片付けます。
開発用スタック（`task compose-up` の 5432 / 6379）とポートを分けているため、`task be-run` で
バックエンドをホスト起動したまま `task test` を並行実行できます。

### プロパティベーステストとミューテーションテスト

プロパティベーステストはQuickTheories 0.26をJUnit Jupiterのテストメソッド内で使います。
固定例の代わりに無作為な値を並べるのではなく、往復則、冪等性、順序不変性、境界保存など、対象コードが満たす不変条件を検証します。
通常の`task test`で具体例テストと一緒に実行され、失敗時にはseedと縮小された反例が出力されます。

PIT 1.22.1は手書き業務コードへ変異を加え、既存テストが変化を検出できるかを測ります。
jOOQ生成コード、起動クラス、Springの設定と外部ライブラリを接続する配線クラスは対象外です。
業務コードがまだ存在しない現状では、変異対象がない実行を正常終了させます。
実行コストが高いため通常の`task verify`には含めず、次のコマンドで明示実行します。

```bash
task mutation-test
```

このタスクはテスト専用のPostgreSQLとRedisを起動し、マイグレーション検証後にPITを実行してから片付けます。
変異対象が存在するとき、HTMLとXMLのレポートは`backend/build/reports/pitest/`へ出力されます。
導入時点では業務ロジックがないためスコア閾値を設けず、業務モジュール追加後に実測した基準値から設定します。
GitHub Actionsでは`Backend CI (Gradle)`を手動実行したときだけミューテーションテストを実行し、レポートを成果物として保存します。
判断の理由と採用候補の比較は[ADR-012](adr/ADR-012-adopt-property-based-and-mutation-testing.md)を参照してください。

## シークレットスキャン（betterleaks）

| 実行タスク              | 内容                                                  |
| ----------------------- | ----------------------------------------------------- |
| `task scan-secrets`     | ステージ済みの変更をスキャン（pre-commit 相当）       |
| `task scan-secrets-all` | リポジトリ全体（履歴含む）をスキャン（pre-push 相当） |

## 静的解析（Semgrep）

Semgrep OSS（コミュニティエディション）で静的解析を行います（`.kiro` / `aidlc` は対象外）。

| 実行タスク          | 内容                                                    |
| ------------------- | ------------------------------------------------------- |
| `task lint-semgrep` | 静的解析（Semgrep OSS / Docker 実行、検出があれば失敗） |

## 静的解析・脆弱性スキャン（Snyk・任意）

Snyk は任意導入です。利用にはアカウント作成が必要で、本プロジェクトは free プランで運用しています。
`.snyk` にスキャン除外ポリシーを定義し、`.kiro` / `aidlc` / `.agents`（いずれも AI-DLC のフレームワークコードでプロダクションコードではない）を対象から除外しています。

## 脆弱性スキャン（Trivy）

依存関係の脆弱性を Trivy でスキャンします。backend は CycloneDX SBOM 経由、frontend は依存を解決してからスキャンします。

| 実行タスク                 | 内容                                                       |
| -------------------------- | ---------------------------------------------------------- |
| `task scan-vulns`          | backend + frontend の脆弱性スキャン（Trivy / Docker 実行） |
| `task scan-vulns-backend`  | backend（Gradle）の脆弱性スキャン（SBOM 経由）             |
| `task scan-vulns-frontend` | frontend（pnpm）の脆弱性スキャン（依存解決後）             |

## GitHub Actions ワークフロー

| 実行タスク                   | 内容                                     |
| ---------------------------- | ---------------------------------------- |
| `task lint-actions`          | ワークフローの Lint（actionlint）        |
| `task lint-actions-security` | ワークフローのセキュリティ解析（zizmor） |

## Docker / Compose

| 実行タスク               | 内容                                                                  |
| ------------------------ | --------------------------------------------------------------------- |
| `task lint-docker`       | Dockerfile のベストプラクティス検査（hadolint）                       |
| `task lint-docker-check` | Dockerfile の Docker 公式チェック（docker build --check）             |
| `task lint-compose`      | Compose ファイルの構文・参照・変数展開の検証（docker compose config） |

## Markdown Lint（markdownlint-cli2）

Markdown ファイルの体裁を markdownlint-cli2 で検査します。除外設定は `.markdownlint-cli2.yaml` の `ignores` に従います（`.kiro` 配下は steering のみ対象）。

| 実行タスク         | 内容                                              |
| ------------------ | ------------------------------------------------- |
| `task lint-md`     | Markdown の Lint（検出があれば失敗）              |
| `task lint-md-fix` | Markdown の Lint 自動修正（安全に直せる項目のみ） |

## リリース設定

release-pleaseの設定と版ファイルの一致を検証する。

| 実行タスク           | 内容                                                                    |
| -------------------- | ----------------------------------------------------------------------- |
| `task release-check` | manifest、`version.txt`、Gradle版、release-please設定の一致を検証する   |

## Taskfile Lint（Task 本体）

`Taskfile.yml` を Task 本体で解析し、YAML 構文とスキーマ構造（未知のキー、型の誤り、不正な構造）を検証します。
Task 専用の公式リンタは存在しないため、Task 自身がファイルを読み込めるか（`task --list-all`）を権威ある検証として使います。
`cmds` 内の Go テンプレートは実行時に評価されるため、このタスクでは検出しません。エディタ側のフル JSON スキーマ検証は Red Hat YAML 拡張（`.vscode/extensions.json` の `redhat.vscode-yaml`）が担います。

| 実行タスク           | 内容                                                      |
| -------------------- | --------------------------------------------------------- |
| `task lint-taskfile` | Taskfile の YAML 構文とスキーマ構造を検証（検出時は失敗） |

## 自動実行（Git フック / CI）

- **Git フック（Lefthook, [`lefthook.yml`](../lefthook.yml)）**
  - commit-msg: commitlint（コミットメッセージを Conventional Commits 規約で検証）
  - pre-commit: betterleaks（ステージ済み）、hadolint / docker build --check（Dockerfile 変更時）、compose config（Compose 変更時）、markdownlint（Markdown 変更時）
  - pre-push: betterleaks（全履歴）、be-lint（Spotless + PMD + SpotBugs）/ be-test（`task test`）（backend 変更時）、actionlint / zizmor（ワークフロー変更時）
- **CI（GitHub Actions, [`.github/workflows/`](../.github/workflows/)）**
  - `backend-ci.yml`（backend の Lint（Spotless + PMD + SpotBugs）とテスト・カバレッジ）、`conventional-commits.yml`（Pull Requestタイトルのcommitlint）、`betterleaks.yml`（シークレットスキャン）、`semgrep.yml`（静的解析 / SARIF アップロード）、`trivy.yml`（脆弱性スキャン / SARIF アップロード）、`actionlint.yml` / `zizmor.yml`（ワークフロー）、`hadolint.yml`（Dockerfile Lint、docker build --check、backend イメージのビルド・起動・ヘルスチェック）、`compose-config.yml`（Compose）、`markdownlint.yml`（Markdown）、`release-please.yml`（リリース設定検証とRelease Pull Request作成）
  - `semgrep.yml` / `trivy.yml` の検出結果は GitHub Code Scanning（Security タブ）に SARIF 形式でアップロードされます。
