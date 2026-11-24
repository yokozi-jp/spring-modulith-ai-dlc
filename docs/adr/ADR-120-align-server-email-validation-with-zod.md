---
type: ADR
title: 'ADR-120: メールアドレスの形式の検査で、サーバーの式をクライアントの Zod の式に揃える'
description: サーバーの `@Email` がクライアントの `z.email()` より緩いずれを、`@Email` に Zod と同じ式を足した `@MailAddress` で埋め、式の一致をテストで検査する。
tags: [adr, backend, frontend, validation, mail]
---

# ADR-120: メールアドレスの形式の検査で、サーバーの式をクライアントの Zod の式に揃える

## Status

Proposed

## Date

2026-11-24

## Context

[メールアドレスの扱い](../mail/email-address.md)は、形式の検査にバックエンドの `@Email` とフロントエンドの Zod の `z.email()` を使うとしていた。
2 つの検査が通す形は同じではない。
`user@localhost`、`a@b`、`user@例え.jp`、`ユーザー@example.com`、`user@[1.2.3.4]`、`user@a_b.com` を、サーバーは通し、クライアントは断る。
[フォームの入力検証](../frontend/form-validation.md)は、サーバーがクライアントと同じかそれ以上の検証をすることを求める。
API をフロントエンドを通さずに呼ぶと、クライアントが断る形のアドレスが保存されうる。

クライアントの schema は OpenAPI から Orval が生成し、`format: email` から `z.email()` を作る。
フロントエンドで `z.email()` を手書きしない方針は変えない。
springdoc は `@Email(regexp)` の `regexp` を OpenAPI の `pattern` に出さない。

## Decision

`shared.validation` に、`@Email(regexp = <Zod 4 の z.regexes.email と同じ式>)` を合成した制約 `@MailAddress` を置き、メールアドレスを受け取る DTO の項目に付ける。
式は `MailAddress.PATTERN` の 1 か所に置く。
OpenAPI には `format: email` だけを出し、`pattern` は足さない。
前後の空白は、フォームの component `EmailInput` が検査の前に除く。

式の一致は、`openapi/mail-address-cases.json` で検査する。
表は、式の文字列と、通す入力と断る入力を持つ。
バックエンドの `MailAddressTest` は `PATTERN` が表の式と同じ文字列であることと、入力の判定を検査する。
フロントエンドの `mail-address-cases.test.ts` は `z.regexes.email.source` が表の式と同じ文字列であることと、入力の判定を検査する。
Zod が式を変えると、フロントエンドのテストが失敗する。

ASCII 以外のローカルパートと IDN のドメインは、サーバーもクライアントも断る。

## Consequences

### Positive

- サーバーの検査が、クライアントが通す形の部分集合になり、フロントエンドを通さない呼び出しでも、クライアントが断る形を保存しない。
- 式の正本はバックエンドの定数 1 つで、Zod との一致はテストが保つ。

### Negative

- Zod の式の写しをバックエンドに持つ。Zod の更新で式が変わったら、人が `PATTERN` と表を直す。
- RFC 6531 で有効な、ASCII 以外のアドレスを断る。
- OpenAPI の契約は `format: email` のままで、JSON Schema の `email` は `user@localhost` を許す。契約だけを読むクライアントには、サーバーの検査より緩く見える。

### Neutral

- `@Email` の検査も働くので、`a@x-.com` のようにクライアントが通してサーバーが断る形が少しある。
- `product` など、`allowedDependencies` が `shared` を含まないモジュールは、使う前に依存を足す。

## Alternatives Considered

### 選択肢1: OpenAPI に `pattern` を手で置く

- **Description**：契約に式を書き、Orval が `.regex()` を生成する。
- **Pros**：契約だけで両側に効く。
- **Cons**：`openapi/openapi.yaml` は生成物で（[ADR-052](ADR-052-commit-openapi-contract-and-check-generated-client.md)）、手で書けない。式が契約とサーバーの 2 か所になる。

### 選択肢2: 独自の Validator を作る

- **Description**：`ConstraintValidator` で `java.util.regex` かライブラリを使う。
- **Pros**：Hibernate の検査に縛られない。
- **Cons**：検査のコードが増え、springdoc が `format: email` を出さなくなる。

### 選択肢3: Zod 側を緩める

- **Description**：クライアントで `z.email()` の代わりに緩い式を手書きする。
- **Pros**：サーバーとの差がなくなる。
- **Cons**：Orval の生成物を使う方針に反する。

## References

- [#664](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/664)
- [メールアドレスの扱い](../mail/email-address.md)
- [フォームの入力検証](../frontend/form-validation.md)
