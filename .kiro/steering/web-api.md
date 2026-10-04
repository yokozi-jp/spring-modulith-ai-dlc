---
inclusion: fileMatch
fileMatchPattern: ["backend/src/main/java/**/presentation/**", "backend/src/main/java/com/example/demo/OpenApiConfig.java", "backend/src/test/java/com/example/demo/OpenApiContractTest.java", "openapi/**", ".spectral.yaml"]
name: web-api
description: HTTP API の controller、OpenAPI の設定、API の契約テストを追加や変更するときに使う。パス、メソッド、パラメータ、応答、ステータスコード、互換性の規約の入口を示す。
---

# Web API の入口

詳細は `docs/web-api/index.md` から必要な文書だけ読む。

## 行動指針

- API の契約を変える前に、`docs/adr/ADR-013-standardize-http-api-contracts.md` と該当する docs を読む。
- 既存 API の互換性を壊す変更は、`docs/web-api/versioning.md` で判定してから行う。
- Controller や API の DTO を変えたら `task api-gen` を実行し、`openapi/openapi.yaml` と `frontend/src/api/generated` を同じコミットに含める。
- OpenAPI のアノテーションと Javadoc は `docs/web-api/openapi-annotations.md` に従う。
  手順は `docs/web-api/runbook-api-change.md` にある。
