.PHONY: setup be-format be-lint be-sbom

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

## バックエンドの SBOM 生成（CycloneDX 形式）
## 出力先: backend/build/reports/
be-sbom:
	cd backend && ./gradlew cyclonedxBom
