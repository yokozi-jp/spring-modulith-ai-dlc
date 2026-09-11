# 厳格な既定（規約: .kiro/steering/makefile-best-practices.md）。
# レシピ全体を単一シェル（.ONESHELL）の bash strict mode で実行し、失敗時は生成途中のターゲットを削除する。
# .ONESHELL のため、複数行レシピは行をまたいで状態を共有する。ディレクトリ移動はサブシェルに閉じ込め、
# 2 行目以降の行頭に @/-/+ を置かない（1 行目の接頭辞だけがレシピ全体に適用される）。
SHELL := bash
.ONESHELL:
.SHELLFLAGS := -eu -o pipefail -c
.DELETE_ON_ERROR:
MAKEFLAGS += --warn-undefined-variables
MAKEFLAGS += --no-builtin-rules

DOCKER ?= $(shell command -v docker)
COMPOSE_ENV_FILE ?= .env
COMPOSE_FILE ?= docker/compose.yml
COMPOSE = $(DOCKER) compose --env-file $(COMPOSE_ENV_FILE) -f $(COMPOSE_FILE)

# テスト専用の依存スタック（PostgreSQL + Redis）。開発用とポート/プロジェクトを分ける。
TEST_ENV_FILE ?= .env.test
TEST_COMPOSE_FILE ?= docker/compose-test.yml
TEST_COMPOSE = $(DOCKER) compose -f $(TEST_COMPOSE_FILE)

# コマンドラインから渡す変数（未指定時は空）。
# 明示的に空を定義し、--warn-undefined-variables の警告を避ける。
DB_ROLLBACK_TAG ?=
CONFIRM_ROLLBACK ?=
CONFIRM_RESET ?=

.PHONY: help dev check verify e2e adr-check setup be-run be-migrate be-migrate-dev be-release-migrate be-schema-tag-check be-verify-migrations be-rollback-check be-rollback-preview be-rollback be-generate-jooq be-refresh-jooq be-format be-lint be-test be-test-dev test test-dev test-deps-up test-deps-down be-sbom compose-up compose-up-backend compose-down compose-ps compose-logs keycloak-logs keycloak-reimport oidc-check compose-reset scan-secrets scan-secrets-all lint-actions lint-actions-security lint-docker lint-docker-check lint-compose lint-md lint-md-fix lint-semgrep scan-vulns scan-vulns-backend scan-vulns-frontend

# 引数なしの make はヘルプを表示する（setup を誤って実行しないため）。
.DEFAULT_GOAL := help

## 各ターゲットの一覧と説明を表示（引数なしの make でも表示）
help:
	@awk 'BEGIN { FS = ":" } \
		/^##@ / { printf "\n\033[1m%s\033[0m\n", substr($$0, 5); next } \
		/^## / { if (doc == "") doc = substr($$0, 4); next } \
		/^[a-zA-Z0-9][a-zA-Z0-9_-]*:/ { if (doc != "") { printf "  \033[36m%-24s\033[0m %s\n", $$1, doc; doc = "" } next } \
		{ doc = "" }' $(MAKEFILE_LIST)

##@ Workflow（日々の入口）

## 依存を起動してバックエンドを起動（日々の入口。初回・changeset追加後は先に be-migrate）
dev: compose-up
	@echo "依存を起動しました。初回・changeset追加後は make be-migrate を実行してください。"
	$(MAKE) be-run

## 素早いローカル確認（バックエンドの静的解析）。こまめに回す。
check: be-lint

## push 前の総合ゲート（静的解析＋隔離テスト）。CI と同じ内容。
verify: be-lint test

## E2E（未整備）。docs/e2e-testing-strategy.md の方針に沿って構築予定。
e2e:
	@echo "E2E はまだ整備されていません。docs/e2e-testing-strategy.md を参照してください。"
	echo "整備後は「フルスタック起動→シード→playwright test」をこのターゲットに束ねます。"

##@ ADR
## 判断が絡む変更に ADR が伴うかを確認（push 前のナッジ）
## ADR_DIFF_BASE で比較起点を変更（既定 origin/main）。ADR_ACK=1 で抑制、ADR_STRICT=1 でブロック。
adr-check:
	@set -eu; \
	base="$${ADR_DIFF_BASE:-origin/main}"; \
	if ! git rev-parse --verify --quiet "$$base" >/dev/null; then \
		echo "ADR check: 比較起点 $$base が無いためスキップします。" >&2; \
		exit 0; \
	fi; \
	changed="$$(git diff --name-only "$$base"...HEAD)"; \
	if [ -z "$$changed" ]; then exit 0; fi; \
	sig="$$(printf '%s\n' "$$changed" | grep -E '^(frontend/|backend/build\.gradle|backend/buildSrc/|backend/gradle/[^/]+\.gradle|backend/src/main/resources/application(-[^/]+)?\.yaml|backend/src/main/resources/db/changelog/|backend/src/main/java/com/example/demo/SecurityConfig\.java|docker/|infrastructure/|\.github/workflows/)' || true)"; \
	if [ -z "$$sig" ]; then exit 0; fi; \
	adr="$$(printf '%s\n' "$$changed" | grep -E '^docs/adr/ADR-[0-9]+.*\.md$$' || true)"; \
	if [ -n "$$adr" ]; then exit 0; fi; \
	if [ "$${ADR_ACK:-}" = "1" ]; then \
		echo "ADR check: ADR_ACK=1 のため警告を抑制しました。" >&2; \
		exit 0; \
	fi; \
	echo "" >&2; \
	echo "⚠ ADR 未追加の可能性: 判断が絡む変更が含まれますが docs/adr/ の更新がありません。" >&2; \
	echo "  対象の変更:" >&2; \
	printf '    - %s\n' $$sig >&2; \
	echo "  重要な設計判断なら docs/adr/ に ADR を追加してください（規約: .kiro/steering/adr-decision-record.md）。" >&2; \
	echo "  該当しない場合は ADR_ACK=1 を付けて再実行できます（例: ADR_ACK=1 git push）。" >&2; \
	echo "" >&2; \
	if [ "$${ADR_STRICT:-}" = "1" ]; then exit 1; fi; \
	exit 0

##@ Setup
## 開発環境の初期セットアップ（全スクリプトを順次実行）
## 実行後に source ~/.bashrc が必要
setup:
	cd docs/local-env-setup && \
		./01-setup-java.sh && \
		./02-setup-viteplus.sh && \
		./03-setup-kiro.sh && \
		export PATH="$$HOME/.local/bin:$$PATH" && \
		./04-setup-shell.sh && \
		./05-setup-bun.sh && \
		./06-setup-go-betterleaks.sh

##@ Backend（実行）
## バックエンドをホスト上で起動（application.yaml がルートの .env を読み込む）
## DBマイグレーションは実行しないため、初回やchangeset追加後は先に be-migrate を実行する。
be-run:
	cd backend && ./gradlew bootRun

##@ Database（マイグレーション & jOOQ）
## changelogをgradle.propertiesの現在スキーマタグまで明示的に適用し、タグの存在も確認する。
be-migrate:
	set -a; \
	. ./$(COMPOSE_ENV_FILE); \
	set +a; \
	cd backend && ./gradlew migrateDatabase

## 作りかけを含む全changesetを開発DBへ適用（Liquibase update。タグ規則なし・タグ確定前の開発用）。
## リリース手前の「現在タグまで」確定適用は be-migrate を使う。
be-migrate-dev:
	set -a; \
	. ./$(COMPOSE_ENV_FILE); \
	set +a; \
	cd backend && ./gradlew update

## 本番向けマイグレーションとrollbackはローカル.envを読まず、CI/CDが注入した専用資格情報だけを使う。
.PHONY: _require-migration-env
_require-migration-env:
	@test -n "$${MIGRATION_DB_URL:-}" || { echo "MIGRATION_DB_URLを指定してください。" >&2; exit 1; }
	test -n "$${MIGRATION_DB_USERNAME:-}" || { echo "MIGRATION_DB_USERNAMEを指定してください。" >&2; exit 1; }
	test -n "$${MIGRATION_DB_PASSWORD:-}" || { echo "MIGRATION_DB_PASSWORDを指定してください。" >&2; exit 1; }
	test -n "$${MIGRATION_DB_SCHEMA:-}" || { echo "MIGRATION_DB_SCHEMAを指定してください。" >&2; exit 1; }

## 本番向けマイグレーション。タグ名はGit管理し、実行時入力を不要にする。
be-release-migrate: _require-migration-env
	cd backend && ./gradlew migrateDatabase

## 現在のスキーマタグが対象DBへ適用済みであることを確認
## ローカルでは.envを読み、本番では注入済みのMIGRATION_DB_*をそのまま使う。
be-schema-tag-check:
	@set -eu; \
	if [ -f "./$(COMPOSE_ENV_FILE)" ]; then \
		set -a; \
		. "./$(COMPOSE_ENV_FILE)"; \
		set +a; \
	fi; \
	cd backend && ./gradlew checkCurrentSchemaTag

## 使い捨てテストDBで全changesetのupdate、rollback、再updateと現在タグを検証
be-verify-migrations:
	set -a; \
	. ./$(TEST_ENV_FILE); \
	set +a; \
	cd backend && ./gradlew verifyDatabaseMigrations

## 切り戻し対象タグが存在するか確認
be-rollback-check: _require-migration-env
	@set -eu; \
	if [ -z "$(DB_ROLLBACK_TAG)" ]; then echo "DB_ROLLBACK_TAGを指定してください。" >&2; exit 1; fi; \
	cd backend && ./gradlew checkRollbackTag -PliquibaseTag="$(DB_ROLLBACK_TAG)"

## DBを変更せず、指定タグまでの切り戻しSQLを生成
## 出力先: backend/build/reports/liquibase/rollback-preview.sql
be-rollback-preview: _require-migration-env
	@set -eu; \
	if [ -z "$(DB_ROLLBACK_TAG)" ]; then echo "DB_ROLLBACK_TAGを指定してください。" >&2; exit 1; fi; \
	mkdir -p backend/build/reports/liquibase; \
	cd backend && ./gradlew previewDatabaseRollback \
		-PliquibaseTag="$(DB_ROLLBACK_TAG)" \
		-PliquibaseOutputFile=build/reports/liquibase/rollback-preview.sql

## 指定タグより後のchangesetを切り戻す
## rollback preview、DBバックアップ、影響確認後にCONFIRM_ROLLBACK=yesを指定する。
be-rollback: _require-migration-env
	@set -eu; \
	if [ -z "$(DB_ROLLBACK_TAG)" ]; then echo "DB_ROLLBACK_TAGを指定してください。" >&2; exit 1; fi; \
	if [ "$(CONFIRM_ROLLBACK)" != "yes" ]; then \
		echo "切り戻すにはrollback previewとバックアップを確認し、CONFIRM_ROLLBACK=yesを指定してください。" >&2; \
		exit 1; \
	fi; \
	cd backend && ./gradlew rollbackDatabase \
		-PliquibaseTag="$(DB_ROLLBACK_TAG)" \
		-PconfirmRollback=true

## 現在のDBスキーマからjOOQソースを生成（マイグレーションは実行しない）
be-generate-jooq:
	set -a; \
	. ./$(COMPOSE_ENV_FILE); \
	set +a; \
	cd backend && ./gradlew jooqCodegen

## Liquibase適用後の最新DBからjOOQソースを生成
be-refresh-jooq:
	set -a; \
	. ./$(COMPOSE_ENV_FILE); \
	set +a; \
	cd backend && ./gradlew migrateAndGenerateJooq

##@ Backend（フォーマット・静的解析・テスト）
## バックエンドのコードフォーマット適用（Spotless）
be-format:
	cd backend && ./gradlew spotlessApply

## バックエンドの静的解析（PMD + SpotBugs + Spotless チェック。main/test 両方）
be-lint:
	cd backend && ./gradlew spotlessCheck pmdMain pmdTest spotbugsMain spotbugsTest

## バックエンドのテスト実行（依存が起動済みの前提。フック/CI・test ターゲットの部品）。
## 開発用ルート .env ではなく .env.test を読み、テスト前にLiquibaseを明示実行する。
be-test:
	set -a; \
	. ./$(TEST_ENV_FILE); \
	set +a; \
	cd backend && SPRING_CONFIG_IMPORT="optional:file:../$(TEST_ENV_FILE)[.properties]" ./gradlew migrateDatabase test

## 作りかけを含む全changesetをテストDBへ適用してからテスト（依存起動済み前提。test-dev の部品）。
## be-test はタグ確定後の確定適用（migrateDatabase）、be-test-dev は開発中の全適用（update）で回す。
be-test-dev:
	set -a; \
	. ./$(TEST_ENV_FILE); \
	set +a; \
	cd backend && SPRING_CONFIG_IMPORT="optional:file:../$(TEST_ENV_FILE)[.properties]" ./gradlew update test

## テスト専用の依存スタック（PostgreSQL 5433 / Redis 6380）を起動
test-deps-up:
	$(TEST_COMPOSE) up -d --wait

## テスト専用の依存スタックを停止しボリュームごと削除（使い捨て）
test-deps-down:
	$(TEST_COMPOSE) down --volumes --remove-orphans

## 隔離した依存を起動してテストを実行し、終了後に必ず後片付けする（ワンショット）。
## .env.test で 5433/6380 を使うため、開発用スタック（5432/6379）や make be-run と衝突しない。
test:
	@set -eu; \
	cleanup() { \
		status=$$?; \
		trap - EXIT; \
		cleanup_status=0; \
		$(TEST_COMPOSE) down --volumes --remove-orphans || cleanup_status=$$?; \
		if [ "$$status" -ne 0 ]; then exit "$$status"; fi; \
		exit "$$cleanup_status"; \
	}; \
	trap cleanup EXIT; \
	$(TEST_COMPOSE) up -d --wait; \
	$(MAKE) be-verify-migrations; \
	$(MAKE) be-test

## 隔離した依存を起動し、作りかけ含む全changesetを適用してテスト、終了後に必ず後片付け（ワンショット・開発用）。
## make test はタグ確定後の確定適用＋rollback検証、make test-dev はタグ確定前の全適用でテストだけ回す。
## rollback検証も要るときは make be-verify-migrations を併用する（タグ未確定でも動く）。
test-dev:
	@set -eu; \
	cleanup() { \
		status=$$?; \
		trap - EXIT; \
		cleanup_status=0; \
		$(TEST_COMPOSE) down --volumes --remove-orphans || cleanup_status=$$?; \
		if [ "$$status" -ne 0 ]; then exit "$$status"; fi; \
		exit "$$cleanup_status"; \
	}; \
	trap cleanup EXIT; \
	$(TEST_COMPOSE) up -d --wait; \
	$(MAKE) be-test-dev

## バックエンドの SBOM 生成（CycloneDX 形式）
## 出力先: backend/build/reports/
be-sbom:
	cd backend && ./gradlew cyclonedxBom

##@ Compose & サービス
## ローカル依存サービスを起動（backend コンテナは起動しない）
compose-up:
	$(COMPOSE) up -d --wait

## backend profile を含む全サービスをビルド・起動
compose-up-backend:
	$(COMPOSE) --profile backend up -d --build --wait

## Compose サービスを停止（named volume は保持）
compose-down:
	$(COMPOSE) --profile backend down

## Compose サービスの状態を表示
compose-ps:
	$(COMPOSE) --profile backend ps

## Compose サービスのログを追跡
compose-logs:
	$(COMPOSE) --profile backend logs --follow

## Keycloak のログだけを追跡
keycloak-logs:
	$(COMPOSE) logs --follow keycloak

## Keycloak のローカルデータだけを削除し、realm.json を再投入して起動
## PostgreSQL、Redis、Grafana の named volume は保持する。
keycloak-reimport:
	@set -eu; \
	$(COMPOSE) create keycloak >/dev/null; \
	container_id=$$($(COMPOSE) ps -aq keycloak); \
	volume=$$($(DOCKER) inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/keycloak/data"}}{{.Name}}{{end}}{{end}}' "$$container_id"); \
	if [ -z "$$volume" ]; then \
		echo "Keycloak data volume を特定できませんでした。" >&2; \
		exit 1; \
	fi; \
	$(COMPOSE) rm --stop --force keycloak; \
	$(DOCKER) volume rm "$$volume"; \
	$(COMPOSE) up -d --wait keycloak; \
	echo "Keycloak realm を再投入しました。"

## Keycloak の OIDC discovery と PKCE S256 対応を確認
## Keycloak が起動済みであること。
oidc-check:
	@$(COMPOSE) exec -T keycloak /bin/bash -ec '\
		exec 3<>/dev/tcp/127.0.0.1/8080; \
		printf "GET /realms/spring-modulith/.well-known/openid-configuration HTTP/1.1\r\nHost: localhost:8080\r\nConnection: close\r\n\r\n" >&3; \
		response="$$(cat <&3)"; \
		printf "%s" "$$response" | grep -q "200 OK"; \
		printf "%s" "$$response" | grep -q "\"issuer\":\"http://localhost:8080/realms/spring-modulith\""; \
		printf "%s" "$$response" | grep -q "\"S256\""'
	echo "OIDC discovery と PKCE S256 を確認しました。"
	echo "Login URL: http://localhost:18080/oauth2/authorization/web"

## Compose サービスと named volume を削除して初期化（全サービスのデータを削除）
## 破壊的操作のため CONFIRM_RESET=yes を要求する（例: make compose-reset CONFIRM_RESET=yes）。
compose-reset:
	@set -eu; \
	if [ "$(CONFIRM_RESET)" != "yes" ]; then \
		echo "全サービスのデータを削除して初期化します。実行するには CONFIRM_RESET=yes を指定してください（例: make compose-reset CONFIRM_RESET=yes）。" >&2; \
		exit 1; \
	fi
	$(COMPOSE) --profile backend down --volumes --remove-orphans

##@ Security（シークレットスキャン）
## ステージ済みの変更をシークレットスキャン（betterleaks / pre-commit 相当）
scan-secrets:
	PATH="$$PATH:$$HOME/go/bin" betterleaks git --staged --no-banner --redact

## リポジトリ全体（履歴含む）をシークレットスキャン（betterleaks / pre-push 相当）
scan-secrets-all:
	PATH="$$PATH:$$HOME/go/bin" betterleaks git . --no-banner --redact --git-workers=16

##@ Lint & Format（リポジトリ全体）
## GitHub Actions ワークフローの Lint（actionlint / Docker 実行）
lint-actions:
	docker run --rm -v "$$PWD":/repo -w /repo \
		rhysd/actionlint:1.7.12@sha256:9d36088643581e728c969f35141f88139fec77280b2be23c1f66f8e40e1025e7 \
		-color

## GitHub Actions ワークフローのセキュリティ解析（zizmor / Docker 実行）
lint-actions-security:
	docker run --rm -v "$$PWD":/repo -w /repo \
		ghcr.io/zizmorcore/zizmor:1.29.0@sha256:863026d54f91271b10b60b67ad8054cb37120167e162482597db102b3026a284 .github/workflows/

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
	@files=$$(git ls-files '**/compose.yml' '**/compose.yaml' '**/compose-test.yml' '**/compose-test.yaml' '**/docker-compose.yml' '**/docker-compose.yaml' 'compose.yml' 'compose.yaml' 'compose-test.yml' 'compose-test.yaml' 'docker-compose.yml' 'docker-compose.yaml'); \
	if [ -z "$$files" ]; then \
		echo "Compose ファイルが見つかりません。"; \
	else \
		for f in $$files; do \
			echo "validating: $$f"; \
			docker compose --env-file .env.example -f "$$f" config --quiet || exit 1; \
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

##@ Security（脆弱性スキャン）
## 依存関係の脆弱性スキャン（Trivy / Docker 実行）
## CI と同じ対象・設定でローカル実行する。修正済みの脆弱性は除外する。
## backend は CycloneDX SBOM を、frontend は解決済みの依存をスキャンする。
scan-vulns: scan-vulns-backend scan-vulns-frontend

## backend（Gradle）の脆弱性スキャン（SBOM 経由）
scan-vulns-backend:
	( cd backend && ./gradlew cyclonedxBom )
	docker run --rm -v "$$PWD/backend":/src -w /src \
		-e TRIVY_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-db:2 \
		-e TRIVY_JAVA_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-java-db:1 \
		aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969 \
		sbom build/reports/cyclonedx/application.cdx.json \
			--ignore-unfixed

## frontend（pnpm）の脆弱性スキャン（依存を解決してから）
scan-vulns-frontend:
	( cd frontend && pnpm install --frozen-lockfile )
	docker run --rm -v "$$PWD/frontend":/src -w /src \
		-e TRIVY_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-db:2 \
		-e TRIVY_JAVA_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-java-db:1 \
		aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969 \
		fs . \
			--scanners vuln \
			--ignore-unfixed
