# Lint・テストのリファレンス

静的解析、シークレットと脆弱性のスキャン、テストは、いずれも [`Taskfile.yml`](../Taskfile.yml) のタスクとして実行できます（`task <タスク名>`）。
日常的な使い方（`task check` / `task verify` などの入口タスク）は [README](../README.md#開発コマンド) と [開発ワークフロー](dev-workflow.md) を参照してください。
このドキュメントは、個々のタスクの内容と、Git フックと CI での自動実行の対応をまとめたリファレンスです。

Docker を使うタスク（semgrep / trivy / actionlint / zizmor / hadolint / docker build --check / compose）は、Docker が無い環境ではスキップされます。

## フロントエンド（Vite+）

- **`task fe-format`**：OxfmtでFrontendを整形する。
- **`task fe-check`**：format、Oxlint（Tailwindとshadcn規則を含む）、TypeScript型を非破壊で検査する。
- **`task fe-doctor`**：React固有のwarningとerrorをReact Doctorで検出し、いずれかの検出または5分超過で失敗する。
- **`task fe-coverage`**：VitestのV8 providerで全体branch coverage 85%を検証する。
- **`task fe-test-build`**：coverage付きテストと本番ビルドを実行する。
- **`task fe-verify`**：上記の検査、診断、テスト、ビルドを順番に実行する。

`task fe-format` はファイルを書き換えます。
pre-commitは部分的にステージした変更を壊さないよう、自動修正ではなく `task fe-check` だけを実行します。
pre-pushはFrontend変更がある場合に `task fe-doctor` と `task fe-test-build` を実行します。
React Doctorは外部へのコード送信と依存情報照会を避けるため、telemetryとsupply-chain scanを無効にして実行します。
個別のVite+処理が必要な場合は、`frontend/` で対応する `vp` コマンドを直接実行します。
component testにはTesting Library React、user-event、jsdomを使います。
API境界のmockにはMSW、coverage計測にはVitestと同じversionのV8 providerを使います。
coverageは起動処理と生成route treeを除く手書きproduction code全体を対象にし、branch coverage 85%をCIで強制します。
Frontend CIはcoverageレポートを14日間artifactとして保存します。
MSW serverは最初のAPIテストを追加する変更で設定します。

`@shadcn/lint` はVite+内蔵のOxlint pluginとして登録し、`vp lint` と `vp check` から実行します。
`no-restyle`、`no-raw-colors`、`no-arbitrary-values`、`no-inline-styles`、`require-static-classes`、`no-unknown-classes` をerrorとして有効化しています。
Oxlintのbuilt-in pluginは `eslint`、`unicorn`、`typescript`、`oxc`、`react`、`import`、`vitest`、`jsx-a11y`、`promise` を有効化します。
`correctness`、`suspicious`、`pedantic`、`perf`、`style`、`restriction` の安定カテゴリはすべてerrorとし、開発中の `nursery` は有効化しません。
warningも `denyWarnings` でblockingにし、不要になったdisable directiveはerrorとして検出します。
全カテゴリには相互に矛盾する規則やframeworkの標準構文を禁止する規則も含まれるため、automatic JSX runtime、Viteのdefault export、TanStack Routerのnamed export、CSSの副作用import、Vitestのhookなどに限って個別規則を無効化または調整します。
カテゴリ全体をwarningへ戻したり、ファイル単位でLintを無効化したりしません。
Oxlintの `react/no-danger` は `dangerouslySetInnerHTML` を禁止します。
`no-restricted-properties` と `no-restricted-globals` はHTMLを解釈するDOM APIを禁止し、repository-localの `local-security/no-jsx-srcdoc` はiframeのJSX `srcDoc` を禁止します。
HTML sinkの組み込み規則は、qualified accessを実際の `vp lint` へ渡す統合テストで検証します。
ローカルruleはOxlintのESLint互換JavaScript plugin APIで実装し、Vitestの陽性例と陰性例で検証します。
任意HTML禁止は `vp check` へ集約し、SemgrepやESLintへ同じ規則を重複定義しません。
文字列はReact childrenまたは `textContent` として描画し、HTML描画が業務要件になった場合は個別にlintを抑制せず、sanitizerと単一の描画境界を設計します。
`src/components/ui/**` はcomponent自身が見た目と構造を定義するため、公式の段階導入方針に従って `no-restyle`、`no-arbitrary-values`、`require-static-classes` を無効化し、残る規則は適用します。
設定は `frontend/vite.config.ts` に集約し、別のOxlint設定や重複するlint taskを追加しません。
導入判断とruleの仕様は[@shadcn/lint公式リポジトリ](https://github.com/shadcn-ui/lint)を参照してください。

`eslint-plugin-better-tailwindcss` も同じOxlintへ登録し、class順序、非推奨class、重複class、不要な空白、競合classをerrorとして検査します。
`no-unknown-classes` と `no-concatenated-classes` は `@shadcn/lint` の規則と重複するため有効化しません。
`enforce-canonical-classes` はTailwind解析workerのtimeoutが再現し、`enforce-consistent-line-wrapping` はOxfmtとの間で同じ字下げ差分が再発するため有効化しません。
Tailwind CSS v4のentry pointは `frontend/vite.config.ts` で `src/style.css` に固定しています。
規則の仕様は[eslint-plugin-better-tailwindcss公式リポジトリ](https://github.com/schoero/eslint-plugin-better-tailwindcss)を参照してください。

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
任意HTML禁止はFrontendのOxlintで強制し、Semgrepには同じrepository固有規則を定義しません。

| 実行タスク          | 内容                                                    |
| ------------------- | ------------------------------------------------------- |
| `task lint-semgrep` | 静的解析（Semgrep OSS / Docker 実行、検出があれば失敗） |

## 静的解析・脆弱性スキャン（Snyk・任意）

Snyk は任意導入です。利用にはアカウント作成が必要で、本プロジェクトは free プランで運用しています。
`.snyk` にスキャン除外ポリシーを定義し、`.kiro` / `aidlc` / `.agents`（いずれも AI-DLC のフレームワークコードでプロダクションコードではない）を対象から除外しています。
`frontend/.snyk` には、修正版のない開発依存の脆弱性を、依存経路と期限を限定してignoreする設定を置いています（ADR-029）。

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
  - pre-commit: betterleaks（ステージ済み）、Frontendのformat、lint、型検査、hadolint / docker build --check（Dockerfile 変更時）、compose config（Compose 変更時）、markdownlint（Markdown 変更時）
  - pre-push: betterleaks（全履歴）、FrontendのReact診断、テスト、本番ビルド、be-lint（Spotless + PMD + SpotBugs）/ be-test（`task test`）（backend 変更時）、actionlint / zizmor（ワークフロー変更時）
- **CI（GitHub Actions, [`.github/workflows/`](../.github/workflows/)）**
  - `frontend-ci.yml`（Frontendのformat、lint、型検査、React Doctor、全体branch coverage 85%、React Compilerを有効にした本番ビルド）、`backend-ci.yml`（backend の Lint（Spotless + PMD + SpotBugs）とテスト・カバレッジ）、`conventional-commits.yml`（Pull Requestタイトルのcommitlint）、`betterleaks.yml`（シークレットスキャン）、`semgrep.yml`（静的解析 / SARIF アップロード）、`trivy.yml`（脆弱性スキャン / SARIF アップロード）、`actionlint.yml` / `zizmor.yml`（ワークフロー）、`hadolint.yml`（Dockerfile Lint、docker build --check、backend イメージのビルド・起動・ヘルスチェック）、`compose-config.yml`（Compose）、`markdownlint.yml`（Markdown）、`release-please.yml`（リリース設定検証とRelease Pull Request作成）
  - `semgrep.yml` / `trivy.yml` の検出結果は GitHub Code Scanning（Security タブ）に SARIF 形式でアップロードされます。
