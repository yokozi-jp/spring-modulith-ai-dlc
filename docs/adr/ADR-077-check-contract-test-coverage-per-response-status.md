---
type: ADR
title: 'ADR-077: OpenAPI 契約の operation と status の組ごとに、MockMvc の契約テストの宣言を検査する'
description: 契約テストは @ContractCase で確かめる operation と status の組を宣言し、ApiContractCoverageTest がコミット済みの openapi/openapi.yaml と /error の status の組をすべて宣言と突き合わせる。共通の 500 と GET の 403 は理由付きで除外する決定。
tags: [adr, testing, openapi, web-api]
---

# ADR-077: OpenAPI 契約の operation と status の組ごとに、MockMvc の契約テストの宣言を検査する

## Status

Proposed

## Date

2026-10-11

## Context

[ADR-013](ADR-013-standardize-http-api-contracts.md) は、エラー応答を Problem Details にし、`status` を実際のステータスコードと一致させると決めた。
[ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md) は、生成した契約を `openapi/openapi.yaml` にコミットし、drift を CI で検査すると決めた。
drift の検査は、契約が実装と一致することを確かめる。
契約が宣言した応答を、MockMvc のテストが実際に起こして確かめているかは、どの検査も見ていない。

実際、PR #192 は `/error` が 499 と 599 を返すように変えたが、最初の版には MockMvc の契約テストがなく、レビューで追加した。
`main` の契約を調べると、次の組にテストがなかった。

- 8 つの operation すべての 401。
- 更新の 4 つの operation の 403。
- `POST /api/orders` の 400。
- `/error` が title を持つ 403、404、405、406、409、415。

この検査は、Docker と DB のない環境でも動かしたい。
契約テストの多くは DB を使う `@SpringBootTest` であり、実行した要求を記録する方式では、全テストを走らせないと結果が出ない。

## Decision

契約テストのメソッドに `@ContractCase(method, path, status)` を付け、確かめる operation と status の組を宣言する。
`path` は OpenAPI の path の形（`/api/orders/{orderId}`）で書く。
宣言は、そのテストメソッドがその status を期待していることを表す。

`ApiContractCoverageTest` は、Spring も DB も起動しない JUnit テストとして、次を検査する。

- コミット済みの `openapi/openapi.yaml` の operation と応答の status の組に、`@ContractCase` の宣言がある。
- OpenAPI に載らない `GET /error` の status は、テストの定数 `ERROR_ENDPOINT_STATUSES` に書き、同じく宣言を求める。
- 契約にない組を宣言していない（path の書き間違いと、消した operation の宣言の残りを見つける）。
- 除外のうち、どの組にも当たらなくなったものがない。

契約は springdoc が依存に持つ swagger-core の `Yaml31` で読み、宣言は既存の ArchUnit の `ClassFileImporter` で手書きのテストコードから集める。
新しい依存は加えない。

次の組は、operation ごとのテストを求めず、理由をテストの `EXEMPTIONS` に書いて除外する。

- **すべての operation の 500**：`ApiExceptionHandler` の catch-all が operation に依らず返し、`ApiErrorContractTest` の未処理例外の case で確かめる。
- **GET の 403**：`OpenApiConfig` が全 operation に付けるが、GET は CSRF の検査の対象外で、`/api/**` に権限の規則もないため、起こす経路がない。

除外は OpenAPI の operation の組にだけ当て、`/error` の 500 と 403 には当てない。
`/error` は転送元の status を保つため、この理由が成り立たない。

`main` の欠けていた組には、除外ではなく契約テストを足す。

`test` タスクは、`-PopenapiExport=true` のときを除き、`openapi/openapi.yaml` を入力に宣言する。
入力にしないと、契約だけを変えたときに `test` が up-to-date になり、この検査が走らない。
`-PopenapiExport=true` のときは、[ADR-064](ADR-064-write-openapi-contract-from-test-task-in-ci.md) のとおり出力に宣言しており、同じファイルを入力と出力の両方にしない。

## Consequences

### Positive

- 契約に応答を足して契約テストを書き忘れると、`./gradlew test` が欠けた組を名指しして失敗する。
- `/error` に新しい status を扱わせたら、定数に足すことで同じ検査に乗る。
- DB を使わないので、Docker のない環境でも `./gradlew test --tests '*ApiContractCoverageTest'` で確かめられる。

### Negative

- 宣言は自己申告であり、宣言したテストが実際にその status を期待しているかは検査しない。
  宣言と `andExpect(status()...)` の一致は、レビューで確かめる。
- 契約テストに `@ContractCase` を書く手間が増える。
- `ERROR_ENDPOINT_STATUSES` は手で保つ。
  `ApiErrorController` が扱う status を増やして定数を直し忘れると、検査をすり抜ける。

### Neutral

- 応答の本文の形は、これまでどおり各テストの `andExpect` が確かめ、この検査は status の有無だけを見る。
- 権限の規則を加えて GET の 403 が起きるようになったら、除外を消して case を足す。
- PR #192 が `main` に入ったら、`ERROR_ENDPOINT_STATUSES` に 499 と 599 を足し、`errorEndpointKeepsUndefinedStatus` に `@ContractCase` を付ける。

## Alternatives Considered

### 選択肢1: MockMvc の実行結果を記録して突き合わせる

- **Description**：共有構成の `MockMvcBuilderCustomizer` で、すべての要求のメソッド、handler の path の形、status を記録し、全テストの後に契約と突き合わせる。
- **Pros**：自己申告ではなく、実際に起こした status を数えられる。
- **Cons**：全テストが終わってから判定する仕組み（Gradle のタスクか JUnit の launcher の listener）が要り、DB のない環境では結果が出ない。
  セキュリティの filter が返す 401 と 403 は handler を通らず、path の形を URI から推定する処理も要る。

### 選択肢2: 宣言の一覧を検査のテストに集める

- **Description**：組とテストメソッドの名前の対応を `ApiContractCoverageTest` の定数に書き、メソッドがあることを反射で確かめる。
- **Pros**：注釈の型を足さずに済む。
- **Cons**：宣言がテストから離れ、テストを直す人が宣言に気付きにくい。

### 選択肢3: 契約の status を JUnit の引数にし、operation ごとに自動で要求を作る

- **Description**：`openapi/openapi.yaml` から組を読み、各組の要求を自動で組み立てて MockMvc で送る。
- **Pros**：契約に応答を足すと、テストが自動で増える。
- **Cons**：404、409、422 などは前提のデータと業務の状態に依るため、汎用の要求では起こせない。

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-052: OpenAPI 契約をリポジトリにコミットし、生成物と破壊的変更を CI で検査する](ADR-052-commit-openapi-contract-and-check-generated-client.md)
- [ADR-064: OpenAPI 契約を test タスクで書き出し、Backend CI のテスト二重実行をやめる](ADR-064-write-openapi-contract-from-test-task-in-ci.md)
- [Controller の対応するテスト](../backend/class-roles/controller.md#対応するテスト)
- [PR #192](https://github.com/yokozi-jp/spring-modulith-ai-dlc/pull/192)
