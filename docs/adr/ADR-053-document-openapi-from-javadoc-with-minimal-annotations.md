---
type: ADR
title: 'ADR-053: OpenAPI の説明を Javadoc から生成し、アノテーションを最小限にする'
description: OpenAPI を API 設計書として網羅的に記述するため、説明文を therapi-runtime-javadoc で Javadoc から生成し、アノテーションは tag、operationId、個別のエラー応答、example に限り、共通のエラー応答を OpenApiConfig の customizer で付け、Spectral で検査する決定。
tags: [adr, api, openapi, backend, javadoc]
---

# ADR-053: OpenAPI の説明を Javadoc から生成し、アノテーションを最小限にする

## Status

Proposed

## Date

2026-10-03

## Context

[ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md) は、springdoc が生成する契約をコミットし、API 設計書として公開すると決めた。
設計書として使うには、operation、parameter、tag、schema の property に説明と例が要る。
[設計書と正本の分担](../writing/design-doc-sources-of-truth.md) も、Web API の契約の正本を springdoc が生成する OpenAPI とし、手で書かないとしている。

このリポジトリの規約は、Controller と DTO に Javadoc を書くことを必須にしている（[クラスの役割：Controller](../backend/class-roles/controller.md)）。
springdoc の `@Operation(summary, description)` や `@Schema(description)` に説明を書くと、同じ説明を Javadoc とアノテーションに二重に書くことになる。
二重に書いた説明は、片方だけを直したときにずれる。

springdoc は、therapi-runtime-javadoc が実行時に読める形で残した Javadoc を OpenAPI の説明に使う。
ハンドラの本文は operation の description、本文の最初の文は summary、`@param` は parameter と requestBody、`@return` は成功応答、record の `@param` は property の description になる。
therapi の最新は 0.15.0 で、springdoc 3.1.1 も optional 依存として同じ版を指定している。

一方、springdoc が最初の文を切る位置は `<p>` か「`.` の直後の空白」で、`。` では切らない。
`@Tag(name)` を明示すると、Controller のクラス Javadoc は tag の説明に使われない。
therapi は Markdown の Javadoc（`///`）に対応していない。

エラー応答については、`OpenApiConfig` が Problem Details の schema と、401、403、500 の共通 response を components に定義している。
operation からの参照の付け方は決めていない。
springdoc の既定の `override-with-generic-response: true` は、`@ControllerAdvice` が扱う例外の応答を全 operation に付けるため、その operation で起きない応答まで設計書に載る。

旧リポジトリ spring-modulith-ai-harness は、summary を `@Operation` に書き、`@ApiResponse` を operation ごとに全部書く方式だった。
この方式と `@ParameterObject` を必須にする規約は、このリポジトリの docs と ADR には引き継がれていない。

## Decision

### 説明文

- 説明文は Javadoc を正本とし、therapi-runtime-javadoc 0.15.0（`implementation` と、scribe の `annotationProcessor`）で springdoc に読ませる。
- `@Operation(summary, description)` や `@Schema(description)` に、Javadoc と同じ説明を書かない。
- description と summary は日本語で書き、operationId、tag、スキーマ名は英語にする。
- ハンドラの Javadoc は、1 行目を summary にし、詳細を `<p>` の後に書く。
- record の DTO は、全 component に `@param` を書く。
  docs の例も同じ形に直す。
- tag の説明だけは `@Tag(name, description)` に書く。
- `///`（Markdown Javadoc）は使わない。

### アノテーション

- request と response の DTO の property に `example` を必須にする。
  enum、boolean、`$ref`、object、array の property は除く。
- tag は業務機能のパッケージ名を kebab-case にし、1 operation に 1 つだけ付ける。
  `-controller` で終わらせない。
- operationId は [Web APIの方式とURLの設計](../web-api/api-style.md) の規約のとおり `@Operation(operationId)` で明示する。
- `@ParameterObject` と `@ModelAttribute` で query をまとめて受けない。
  [クエリパラメータ](../web-api/query-parameters.md) の、`@RequestParam` で項目ごとに受ける規約に従う。

### エラー応答

- `OpenApiConfig` に customizer を足し、全 operation に 401、403、500 の共通 response（`$ref: #/components/responses/*Problem`）を付ける。
- 400 は、parameter か requestBody がある operation にだけ付ける。
- 404、409、422 は、operation ごとに `@ApiResponse(responseCode, ref = ...)` で書く。
- components に `NotFoundProblem`、`ConflictProblem`、`UnprocessableContentProblem` を足す。
- `springdoc.override-with-generic-response` を `false` にする。
- `OpenApiConfig` の英語の description（Info と ProblemDetail）を日本語にする。

### 旧リポジトリの方式を採らない理由

規約文書に、次の理由を一行ずつ書く。

- summary を `@Operation` に書かない理由は、Javadoc と同じ説明を二重に保守することになるためである。
- `@ApiResponse` を operation ごとに全部書かない理由は、401、403、500 は全 operation で同じで、customizer で付ければ書き忘れが起きないためである。

### 検査

- Spectral のルールで、operation、parameter、tag、schema の property の description、property の example、tag の形と数、4xx と 5xx の Problem Details の参照、共通 response の content を検査する。
- Spectral の組み込みの warn のルール（`operation-description`、`oas3-parameter-description`、`tag-description`、`operation-singular-tag`、`operation-tag-defined`）を error に上げる。
- Spectral のルールは、spec の形をした合格例と違反例の fixture で検査する。
- Javadoc の反映、`<p>`、customizer、tag、example の実際の出力は、サンプルの Controller を明示的に読み込むテストで検査する。

## Consequences

### Positive

- 説明を Javadoc の一か所に書けば、コードの読み手と設計書の読み手の両方に同じ説明が届く。
- 共通のエラー応答を customizer が付けるため、operation ごとの書き忘れがない。
- 説明と example の欠落を、レビューではなく Spectral で検出できる。
- 設計書に載るエラー応答が、その operation で起きるものに限られる。

### Negative

- therapi の依存が増える。
  0.15.0（2022-07）以降のリリースはなく、Markdown の Javadoc に対応する見込みは分からない。
- 1 行目を summary にするために `<p>` を書く必要があり、通常の Javadoc の書き方とわずかに異なる。
- 404、409、422 の付け忘れは Spectral で検出できず、レビューで確認する。
- `example` を書く手間が property ごとに増える。

### Neutral

- springdoc の Javadoc の扱い（`<p>` が description に残るか、record の `@param`、package-private のハンドラ）は、版を上げたときにテストで確かめ直す。
- 継承や sealed interface の DTO を使うときは、Spectral の検査対象（`allOf` の下の property）を足す。

## Alternatives Considered

### 選択肢1: summary と description をアノテーションに書く

- **Description**：旧リポジトリと同じく、`@Operation(summary, description)` に説明を書く。
- **Pros**：therapi が要らず、springdoc の文書の例と同じ書き方になる。
- **Cons**：Javadoc を必須にしている規約と合わせると、同じ説明を二か所で保守することになる。

### 選択肢2: 全 property に @Schema(description) を書く

- **Description**：DTO の各 property に `@Schema(description = ...)` を付ける。
- **Pros**：説明が property の直上にあり、therapi が要らない。
- **Cons**：record の `@param` と二重になり、アノテーションの量が property の数だけ増える。

### 選択肢3: customizer でクラス Javadoc を tag の説明に写す

- **Description**：`@Tag(name)` だけを書き、customizer が Controller のクラス Javadoc を tag の description に写す。
- **Pros**：tag の説明も Javadoc に一本化できる。
- **Cons**：tag と Controller を対応づけるコードが増え、`@Tag(name, description)` の一行で済むことに比べて得るものが小さい。

### 選択肢4: ArchUnit で @Tag と @Operation を必須にする

- **Description**：Controller のクラスとハンドラにアノテーションがあるかを ArchUnit で検査する。
- **Pros**：コンパイル後すぐに、DB なしで検出できる。
- **Cons**：アノテーションの有無は説明の中身を保証しない。
  Spectral が生成された契約そのものを検査するため、同じ欠落を二系統で検査することになる。

### 選択肢5: @ParameterObject で query をまとめて受ける

- **Description**：旧リポジトリの規約のとおり、検索条件の record を `@ParameterObject` で受ける。
- **Pros**：ハンドラの引数が短くなる。
- **Cons**：[クエリパラメータ](../web-api/query-parameters.md) の、`@RequestParam` で項目ごとに受けて検索条件の record を作る規約と衝突する。

### 選択肢6: override-with-generic-response を true のままにする

- **Description**：springdoc の既定どおり、`@ControllerAdvice` が扱う例外の応答を全 operation に付ける。
- **Pros**：設定と customizer が要らない。
- **Cons**：その operation で起きない応答まで設計書に載り、Orval の生成 client にも起きないエラーの型が入る。

## References

- [ADR-013: HTTP API 契約を標準化する](ADR-013-standardize-http-api-contracts.md)
- [ADR-052: OpenAPI 契約をリポジトリにコミットし、生成物と破壊的変更を CI で検査する](ADR-052-commit-openapi-contract-and-check-generated-client.md)
- [クラスの役割：Controller](../backend/class-roles/controller.md)
- [クエリパラメータ](../web-api/query-parameters.md)
- [Web APIの方式とURLの設計](../web-api/api-style.md)
- [springdoc-openapi: Javadoc support](https://springdoc.org/#javadoc-support)
- [therapi-runtime-javadoc](https://github.com/dnault/therapi-runtime-javadoc)
- [Spectral OpenAPI rules](https://github.com/stoplightio/spectral/blob/develop/docs/reference/openapi-rules.md)
