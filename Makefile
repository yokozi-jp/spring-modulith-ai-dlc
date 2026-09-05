.PHONY: setup be-format be-lint be-test be-coverage be-sbom scan-secrets scan-secrets-all lint-actions lint-actions-security lint-docker lint-docker-check lint-compose lint-md lint-md-fix lint-semgrep scan-vulns scan-vulns-backend scan-vulns-frontend

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
		./06-setup-bun.sh && \
		./07-setup-go-betterleaks.sh

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

## Markdown の Lint（markdownlint-cli2）
## 除外設定は .markdownlint-cli2.yaml の ignores に従う。
lint-md:
	npx --yes markdownlint-cli2 "**/*.md"

## Markdown の Lint 自動修正（markdownlint-cli2 --fix）
## 安全に直せる項目のみ修正する。除外設定は .markdownlint-cli2.yaml に従う。
lint-md-fix:
	npx --yes markdownlint-cli2 --fix "**/*.md"

## 静的解析（Semgrep OSS / Docker 実行）
## CI と同じルール・設定でローカル実行する。コードは外部に送信されない。
lint-semgrep:
	docker run --rm -e SEMGREP_SEND_METRICS=off -v "$$PWD":/src -w /src \
		semgrep/semgrep:1.175.0@sha256:b94b53d02fd4a022f9eac4e2af1380f5c3c4c21400e79d3336bdff1d1db5e796 \
		semgrep scan \
			--config p/default \
			--config p/java \
			--config p/typescript \
			--config p/dockerfile \
			--config p/secrets \
			--exclude .kiro \
			--exclude aidlc \
			--error \
			--metrics off \
			--disable-version-check

## 依存関係の脆弱性スキャン（Trivy / Docker 実行）
## CI と同じ対象・設定でローカル実行する。修正済みの脆弱性は除外する。
## backend は CycloneDX SBOM を、frontend は解決済みの依存をスキャンする。
scan-vulns: scan-vulns-backend scan-vulns-frontend

## backend（Gradle）の脆弱性スキャン（SBOM 経由）
scan-vulns-backend:
	cd backend && ./gradlew cyclonedxBom
	docker run --rm -v "$$PWD/backend":/src -w /src \
		-e TRIVY_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-db:2 \
		-e TRIVY_JAVA_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-java-db:1 \
		aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969 \
		sbom build/reports/cyclonedx/application.cdx.json \
			--ignore-unfixed

## frontend（pnpm）の脆弱性スキャン（依存を解決してから）
scan-vulns-frontend:
	cd frontend && pnpm install --frozen-lockfile
	docker run --rm -v "$$PWD/frontend":/src -w /src \
		-e TRIVY_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-db:2 \
		-e TRIVY_JAVA_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-java-db:1 \
		aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969 \
		fs . \
			--scanners vuln \
			--ignore-unfixed
