.PHONY: backend-sbom

## バックエンドの SBOM 生成（CycloneDX 形式）
## 出力先: backend/build/reports/
backend-sbom:
	cd backend && ./gradlew cyclonedxBom
