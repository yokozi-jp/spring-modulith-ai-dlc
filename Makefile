.PHONY: setup be-format be-lint be-test be-coverage be-sbom scan-secrets scan-secrets-all lint-actions lint-actions-security

## 開発環境の初期セットアップ（全スクリプトを順次実行）
## 実行後に source ~/.bashrc が必要
setup:
	cd docs/local-env-setup && \
		./01-setup-java.sh && \
		./02-setup-viteplus.sh && \
		./03-setup-kiro.sh && \
		export PATH="$$HOME/.local/bin:$$PATH" && \
		./04-setup-shell.sh && \
		./05-setup-lsp.sh && \
		./06-setup-bun.sh

## バックエンドのコードフォーマット適用（Spotless）
be-format:
	cd backend && ./gradlew spotlessApply

## バックエンドの静的解析（PMD + SpotBugs + Spotless チェック）
be-lint:
	cd backend && ./gradlew spotlessCheck pmdMain spotbugsMain

## バックエンドのテスト実行＆カバレッジ検証
be-test:
	cd backend && ./gradlew test

## バックエンドの SBOM 生成（CycloneDX 形式）
## 出力先: backend/build/reports/
be-sbom:
	cd backend && ./gradlew cyclonedxBom

## ステージ済みの変更をシークレットスキャン（betterleaks / pre-commit 相当）
scan-secrets:
	PATH="$$PATH:$$HOME/go/bin" betterleaks git --staged --no-banner --redact

## リポジトリ全体（履歴含む）をシークレットスキャン（betterleaks / pre-push 相当）
scan-secrets-all:
	PATH="$$PATH:$$HOME/go/bin" betterleaks git . --no-banner --redact --git-workers=16

## GitHub Actions ワークフローの Lint（actionlint / Docker 実行）
lint-actions:
	docker run --rm -v "$$PWD":/repo -w /repo \
		rhysd/actionlint:1.7.12@sha256:9d36088643581e728c969f35141f88139fec77280b2be23c1f66f8e40e1025e7 \
		-color

## GitHub Actions ワークフローのセキュリティ解析（zizmor / Docker 実行）
lint-actions-security:
	docker run --rm -v "$$PWD":/repo -w /repo \
		ghcr.io/zizmorcore/zizmor:latest .github/workflows/
