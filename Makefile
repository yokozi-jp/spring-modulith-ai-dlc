.PHONY: setup be-format be-lint be-test be-coverage be-sbom scan-secrets scan-secrets-all lint-actions lint-actions-security lint-docker lint-docker-check lint-compose

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

## Dockerfile のベストプラクティス検査（hadolint / Docker 実行）
## リポジトリ内の全 Dockerfile を対象にする
lint-docker:
	@files=$$(git ls-files '**/Dockerfile' '**/Dockerfile.*' '**/*.Dockerfile' 'Dockerfile'); \
	if [ -z "$$files" ]; then \
		echo "Dockerfile が見つかりません。"; \
	else \
		printf 'linting: %s\n' $$files; \
		docker run --rm -i -v "$$PWD":/repo -w /repo \
			hadolint/hadolint:v2.12.0@sha256:30a8fd2e785ab6176eed53f74769e04f125afb2f74a6c52aef7d463583b6d45e \
			hadolint $$files; \
	fi

## Dockerfile の Docker 公式ベストプラクティスチェック（docker build --check）
## ビルドはせず --check のみ。リポジトリ内の全 Dockerfile を対象にする
lint-docker-check:
	@files=$$(git ls-files '**/Dockerfile' '**/Dockerfile.*' '**/*.Dockerfile' 'Dockerfile'); \
	if [ -z "$$files" ]; then \
		echo "Dockerfile が見つかりません。"; \
	else \
		for f in $$files; do \
			echo "checking: $$f"; \
			docker build --check -f "$$f" "$$(dirname "$$f")" || exit 1; \
		done; \
	fi

## Compose ファイルの構文・参照・変数展開を検証（docker compose config）
## リポジトリ内の全 Compose ファイルを対象にする
lint-compose:
	@files=$$(git ls-files '**/compose.yml' '**/compose.yaml' '**/docker-compose.yml' '**/docker-compose.yaml' 'compose.yml' 'compose.yaml' 'docker-compose.yml' 'docker-compose.yaml'); \
	if [ -z "$$files" ]; then \
		echo "Compose ファイルが見つかりません。"; \
	else \
		for f in $$files; do \
			echo "validating: $$f"; \
			docker compose -f "$$f" config --quiet || exit 1; \
		done; \
	fi
