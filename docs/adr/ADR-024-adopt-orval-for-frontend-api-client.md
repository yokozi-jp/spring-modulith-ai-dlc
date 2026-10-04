---
type: ADR
title: 'ADR-024: Frontend API client生成にOrvalを採用する'
description: OpenAPI と型のずれを防ぐため、Frontend API client 生成に Orval を採用する決定。
tags: [adr, frontend, api, openapi]
---

# ADR-024: Frontend API client生成にOrvalを採用する

## Status

Proposed

## Date

2026-09-29

## Context

バックエンドはSpring Bootとspringdoc-openapiを使い、実行中のControllerからOpenAPI 3.1契約を生成する。
ADR-013はこのcode-first契約をSpectralと契約テストで検査する方針を定めている。
現在の契約には共通のProblem Details schemaとresponseがあるが、業務APIの `paths` はまだない。

FrontendはReact、TanStack Router、TanStack Query、TanStack Form、Zodを採用している。
Spring BootのAPIを手書きのFetch関数から呼ぶと、URL、request、responseのTypeScript型をOpenAPIと別に保守することになる。
両者がずれると、コンパイルが成功しても実行時のrequestまたはresponseが契約と一致しない可能性がある。

OrvalはOpenAPIからFetch関数、TypeScript型、TanStack Query hooks、Zod schemaを生成できる。
一方、up-fetchはnative Fetchを拡張し、base URL、timeout、retry、response parsing、Standard Schema検証などを提供するが、OpenAPIからclientやschemaを生成しない。
両方を最初から重ねると、request生成とtransport設定の境界を追加で設計する必要がある。

ZodはStandard Schema v1に対応し、TanStack Formの入力検証へ直接利用できる。
Orvalの組み込みruntime validationも生成したZod schemaを利用できるため、フォームとAPI生成のschema libraryを一つに揃えられる。

## Decision

Frontend API client generatorにOrvalを採用し、開発依存のバージョンを固定する。
Orvalの推移依存 `esbuild` のinstall scriptは許可せず、pnpmの `allowBuilds` で明示的に無効化する。
Spring Bootが生成してSpectral検査を通したOpenAPI 3.1契約を入力にし、native Fetchを使うTanStack Query client、TypeScript型、Zod schemaを生成する。

初期構成ではup-fetchを追加せず、Orvalの組み込みFetch clientを使う。
認証は同一オリジンのセッションCookieを前提とし、生成clientへ不要なtoken管理を追加しない。
Problem Details、CSRF、timeoutなどの共通処理が組み込みFetchだけでは表現できないと確認した場合に限り、Orval custom mutatorを追加する。
その時点でも、up-fetchを使うか小さなnative Fetch wrapperで足りるかを要件から選ぶ。

フォーム入力と手書きのruntime境界にもZodを使う。
API responseのruntime validationが必要な境界では、OpenAPIと同じ定義からOrvalが生成したZod schemaを使い、schemaを手書きで重複させない。

契約snapshotの置き場所、生成Task、drift検査、生成物のGit管理方針は、最初の業務APIより前に[ADR-051](ADR-051-commit-openapi-contract-and-check-generated-client.md)で確定した。

## Consequences

### Positive

- Spring BootのOpenAPI契約からFrontendのrequest、response、TanStack Query hooksを生成できる。
- API型の手書きと、バックエンド契約からのずれを減らせる。
- native Fetchを使うため、初期構成では追加のruntime HTTP client依存が要らない。
- フォームとAPI生成でZodへ揃え、schema記法、エラー処理、依存更新を一系統にできる。
- OpenAPIから生成したZod schemaでAPI responseをruntimeに検証できる。

### Negative

- Orvalの生成規約と設定を保守し、OpenAPI変更時に生成物を更新する必要がある。
- 生成したTypeScript型とZod schemaによって、生成物の量とFrontend bundleが増える。
- OpenAPIに不正確なoperationId、request、responseがあると、その誤りも生成clientとschemaへ伝わる。

### Neutral

- Orvalは開発時のcode generatorであり、生成されたFetch clientと必要なZod schemaがブラウザで動く。
- up-fetchを恒久的に禁止しない。
  共通transport要件が生じた時点でcustom mutatorの実装候補として再評価する。
- Orvalでのclient生成開始まではHTTP clientを先行実装しない。

## Alternatives Considered

### up-fetchだけを採用する

- **Description**：up-fetchを共通HTTP clientにし、endpoint、TypeScript型、Zod schemaを手書きする。
- **Pros**：ZodをStandard Schemaとしてresponse検証へ渡せ、timeoutやretryも設定できる。
- **Cons**：OpenAPIからclientとschemaを生成できず、Spring Boot契約との重複保守が残る。

### Orvalとup-fetchを最初から併用する

- **Description**：Orvalのcustom mutatorからup-fetchを呼ぶ。
- **Pros**：OpenAPI code generationとup-fetchのtransport機能を組み合わせられる。
- **Cons**：業務APIがない段階で二つの抽象とmutatorを追加し、責務の境界を保守する必要がある。

### Fetch clientを手書きする

- **Description**：native Fetchを直接使い、endpointごとに関数、型、Zod schemaを実装する。
- **Pros**：generator設定と生成物が不要になる。
- **Cons**：OpenAPIとFrontend実装の同期を人手に依存させ、endpoint追加ごとに同じ変換を繰り返す。

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-051: OpenAPI 契約をリポジトリにコミットし、生成物と破壊的変更を CI で検査する](ADR-051-commit-openapi-contract-and-check-generated-client.md)
- [ADR-023: TanStack Form と Zod を採用する](ADR-023-adopt-tanstack-form-and-zod.md)
- [Orval: Fetch](https://orval.dev/docs/guides/fetch/)
- [Orval: Fetch client for TanStack Query](https://orval.dev/docs/guides/fetch-client/)
- [Orval: Custom HTTP client](https://orval.dev/docs/guides/custom-client/)
- [Orval: Zod](https://orval.dev/docs/guides/zod/)
- [up-fetch](https://github.com/L-Blondy/up-fetch)
