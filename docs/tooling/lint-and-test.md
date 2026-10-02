---
type: Reference
title: Lintとテストのリファレンス
description: 静的解析、スキャン、テストのTask仕様と、GitフックおよびCIでの自動実行の対応をまとめ、各検査の内容、成否条件、実行場所を確認するときに読むリファレンス。
tags: [reference, testing, lint, ci]
---

# Lintとテストのリファレンス

検査は[`Taskfile.yml`](../../Taskfile.yml)の公開Taskから実行する。
場面ごとの実行順は[開発ワークフロー](dev-workflow.md)を参照する。
Dockerを使うTaskは、Dockerがないローカル環境ではスキップし、CIで検査する。

## 入口Task

- **`task check`**：バックエンドの静的解析を実行する。
- **`task verify`**：バックエンドの静的解析、マイグレーション検証、テストを実行する。
- **`task fe-verify`**：フロントエンドの静的解析、未使用コード検査、React診断、テスト、本番ビルドを実行する。
- **`task test`**：隔離した依存を起動し、確定済みマイグレーションの検証、バックエンドテスト、OpenAPI契約検査後に片付ける。
- **`task test-dev`**：隔離した依存を起動し、作りかけのchangesetを含むバックエンドテスト後に片付ける。
- **`task mutation-test`**：隔離した依存を使ってバックエンドのPITミューテーションテストを実行する。

## フロントエンド

- **`task fe-check`**：Oxfmt、Oxlint、TypeScriptの型を非破壊で検査する。
- **`task fe-knip`**：未参照ファイル、未使用export、未使用依存をKnipで検査する。
- **`task fe-doctor`**：React Doctorでwarningとerrorを検出し、検出または5分超過で失敗する。
- **`task fe-coverage`**：VitestのV8 providerで全体branch coverage 85%を検証する。
- **`task fe-test-build`**：coverage付きテストと本番ビルドを実行する。
- **`task fe-verify`**：`fe-check`、`fe-knip`、`fe-doctor`、`fe-test-build`を実行する。

## バックエンド

- **`task be-lint`**：Spotless、PMD、SpotBugsでmainとtestを検査する。
- **`task be-openapi-lint`**：生成したOpenAPI 3.1契約をSpectralで検査する。
- **`task be-verify-migrations`**：使い捨てDBでchangesetの適用、rollback、再適用、現在タグを検証する。
- **`task be-test`**：起動済みのテスト用依存に確定済みchangesetを適用し、テストとcoverage検証を実行する。
- **`task be-test-dev`**：起動済みのテスト用依存に作りかけのchangesetを含めて適用し、テストを実行する。
- **`task be-mutation-test`**：起動済みのテスト用依存でPITを実行する。
- **`task test`**：テスト用依存の起動、`be-verify-migrations`、`be-test`、`be-openapi-lint`、後片付けを実行する。
- **`task test-dev`**：テスト用依存の起動、`be-test-dev`、後片付けを実行する。
- **`task mutation-test`**：テスト用依存の起動、マイグレーション検証、`be-mutation-test`、後片付けを実行する。

`task test`と`task mutation-test`は、`docker/compose-test.yml`のPostgreSQL 5433とRedis 6380を`.env.test`で起動する。
PITのHTMLとXMLのレポートは、変異対象がある場合に`backend/build/reports/pitest/`へ出力する。
プロパティベーステストとミューテーションテストの採用理由は[ADR-012](../adr/ADR-012-adopt-property-based-and-mutation-testing.md)を参照する。

## リポジトリ全体

- **`task lint-duplicates`**：生成コードを除く手書きのフロントエンドとバックエンドをjscpdで検査する。
- **`task scan-secrets`**：ステージ済み変更をbetterleaksでスキャンする。
- **`task scan-secrets-all`**：リポジトリ全体と履歴をbetterleaksでスキャンする。
- **`task lint-semgrep`**：Semgrep OSSで静的解析する。
- **`task be-sbom`**：バックエンドのCycloneDX SBOMを生成する。
- **`task scan-vulns`**：バックエンドとフロントエンドの依存関係をTrivyで検査する。
- **`task scan-vulns-backend`**：バックエンドのCycloneDX SBOMをTrivyで検査する。
- **`task scan-vulns-frontend`**：フロントエンドの解決済み依存関係をTrivyで検査する。
- **`task lint-actions`**：GitHub Actionsワークフローをactionlintで検査する。
- **`task lint-actions-security`**：GitHub Actionsワークフローをzizmorで検査する。
- **`task lint-docker`**：Dockerfileをhadolintで検査する。
- **`task lint-docker-check`**：Dockerfileを`docker build --check`で検査する。
- **`task lint-compose`**：Composeファイルの構文、参照、変数展開を検証する。
- **`task otel-collector-check`**：許可していない属性を含むOTLPのログをCollectorに流し、その属性が除かれ、許可した属性が残ることを確かめる。
- **`task lint-md`**：`.markdownlint-cli2.yaml`の除外設定に従いMarkdownを検査する。
- **`task lint-md-fix`**：markdownlint-cli2で安全に修正できるMarkdownの問題を修正する。
- **`task okf-check`**：OKF適合、内部リンク、孤立文書、文書責務の見直し合図、steering境界、Taskfile文書同期候補を検査する。
- **`task release-check`**：release-pleaseのmanifest、版ファイル、Gradle版、設定の一致を検証する。
- **`task lint-taskfile`**：Task本体でTaskfileのYAML構文とスキーマ構造を検証する。
- **`task adr-check`**：判断が絡む変更にADRが伴うかを確認する。

Knipとjscpdの採用理由は[ADR-035](../adr/ADR-035-adopt-jscpd-and-knip-quality-gates.md)を参照する。
OKF検査の採用理由は[ADR-036](../adr/ADR-036-adopt-okf-for-docs-knowledge-bundle.md)と[ADR-038](../adr/ADR-038-route-steering-to-docs-knowledge.md)を参照する。

## Gitフック対応

- **commit-msg（すべて）**：commitlintを実行する。
- **pre-commit（すべて）**：`scan-secrets`相当を実行する。
- **pre-commit（フロントエンドまたはTaskfileの変更）**：`fe-check`を実行する。
- **pre-commit（Dockerfileの変更）**：`lint-docker`相当と`lint-docker-check`相当を実行する。
- **pre-commit（Composeファイルの変更）**：`lint-compose`相当を実行する。
- **pre-commit（Markdownの変更）**：変更ファイルへ`lint-md`相当を実行する。
- **pre-commit（docs、steering、Taskfileの変更）**：`okf-check`を実行する。
- **pre-push（すべて）**：`adr-check`と`scan-secrets-all`相当を実行する。
- **pre-push（フロントエンドまたはTaskfileの変更）**：`fe-doctor`と`fe-test-build`を実行する。
- **pre-push（バックエンドのJavaまたはGradle変更）**：`be-lint`相当と`test`を実行する。
- **pre-push（バックエンドまたはDockerfileの変更）**：バックエンドイメージのbuild stageをビルドする。
- **pre-push（GitHub Actionsワークフローの変更）**：`lint-actions`相当と`lint-actions-security`相当を実行する。

Gitフックの条件とコマンドは[`lefthook.yml`](../../lefthook.yml)を正とする。

## CI対応

- **`frontend-ci.yml`**：`fe-verify`を実行する。
- **`backend-ci.yml`**：`be-lint`相当、`be-verify-migrations`、`be-test`、`be-openapi-lint`、手動実行時の`mutation-test`を実行する。
- **`betterleaks.yml`**：`scan-secrets-all`相当を実行する。
- **`static-analysis.yml`**：`lint-semgrep`相当と`lint-duplicates`を実行する。
- **`trivy.yml`**：`scan-vulns`相当を実行する。
- **`actionlint.yml`**：`lint-actions`相当を実行する。
- **`zizmor.yml`**：`lint-actions-security`相当を実行する。
- **`hadolint.yml`**：`lint-docker`相当、`lint-docker-check`相当、バックエンドイメージのビルドと起動確認を実行する。
- **`compose-config.yml`**：`lint-compose`相当を実行する。
- **`otel-collector.yml`**：`otel-collector-check`を実行する。
- **`markdownlint.yml`**：`lint-md`相当を実行する。
- **`okf-validate.yml`**：`okf-check`を実行する。
- **`release-please.yml`**：`release-check`を実行する。

各ワークフローの実装は[`.github/workflows/`](../../.github/workflows/)を正とする。
