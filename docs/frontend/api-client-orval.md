---
type: Convention
title: OrvalとAPI境界
description: OpenAPI から Orval で生成する API client の設定、operation の tag と operationId、生成物の扱い、custom mutator と runtime 検証の条件を定める。業務 API を追加するとき、API の呼び出し方や生成設定を変えるときに読む。
tags: [convention, frontend, api, orval, openapi]
---

# OrvalとAPI境界

API client、型、schema は OpenAPI から Orval で生成し、同じものを手書きしない。
各 operation には所有する業務機能の tag を一つと安定した `operationId` を付ける。
`api/generated` には手書きのコードを置かない。

## 生成API境界

**生成API境界**は、OpenAPI契約から生成したHTTP client、型、schema、test用mockを置く領域である。

Orvalは、最初の業務APIとGit管理するOpenAPI snapshotを追加する変更で設定する。

初期設定ではTanStack Query client、native Fetch、tag単位の分割を使う。

``` typescript
import { defineConfig } from "orval";

export default defineConfig({
  api: {
    input: {
      target: "./openapi/openapi.json",
    },
    output: {
      target: "./src/api/generated/endpoints",
      schemas: {
        path: "./src/api/generated/models",
        splitByTags: true,
      },
      mode: "tags-split",
      client: "react-query",
      httpClient: "fetch",
      clean: true,
    },
  },
});
```

入力ファイルの最終的な配置と生成commandは、ADR-024に従い、最初の業務APIを追加するときに確定する。

## operationと生成物の扱い

各OpenAPI operationには、所有する業務機能のtagを一つ付け、安定した `operationId` を与える。

Orvalは複数tagがあるoperationを先頭tagへ割り当てるため、一つの所有tagに限定すると生成先が記述順へ依存しない。

生成したendpoint、query key、query options、query Hook、request型、response型を正本として使い、同じ型とFetch関数をfeature内へ手書きしない。

Orvalが生成する `models/shared` は複数tagが参照するschemaの生成先であり、手書きの共有コードを置く `shared` ディレクトリではない。

Orvalの `clean` は生成先を削除して再作成できるため、手書きのmutator、MSW server lifecycle、fixtureを `api/generated` に置かない。

MSW handlerをOrvalから生成する場合は生成先だけを `api/generated/mocks` に置き、手書きのserver setupは必要になった時点で `testing/msw` などの生成対象外へ置く。

## transportとruntime検証

同一オリジンのsession cookieを使うため、tokenの保存とAuthorization headerの注入は追加しない。

Problem Details、CSRF、timeoutなどの共通transport要件を組み込みFetchだけで表現できないと確認した場合に限り、`api/generated` の外へcustom mutatorを追加する。

API responseをruntimeで検証する必要がある境界では、OpenAPIからOrvalが生成するZod schemaを使い、同じschemaを手書きしない。

生成物の再生成と検証の手順は [フロントエンドのテストと検証](testing.md) の「自動生成と検証」に従う。

## 関連資料

- [ADR-024: Frontend API client生成にOrvalを採用する](../adr/ADR-024-adopt-orval-for-frontend-api-client.md)
- [Orval: React Query](https://orval.dev/docs/guides/react-query/)
- [Orval: Output configuration](https://orval.dev/docs/reference/configuration/output/)
