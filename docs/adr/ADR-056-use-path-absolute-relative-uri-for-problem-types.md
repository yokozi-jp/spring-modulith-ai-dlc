---
type: ADR
title: 'ADR-056: problem type にパスを全部書いた相対 URI を使う'
description: 業務固有の problem type を /problems/<kebab-case> の相対 URI にし、入力検証エラーを /problems/validation-error と errors 拡張で返す決定。ADR-013 の HTTPS URI と URI 基点の先送りを上書きする。
tags: [adr, backend, api, error, frontend]
---

# ADR-056: problem type にパスを全部書いた相対 URI を使う

## Status

Accepted

## Date

2026-10-04

## Context

[ADR-013](ADR-013-standardize-http-api-contracts.md) は、入力検証エラーを 400 とし、業務固有の `type` と `errors` 拡張を使うと定めている。
同じ ADR は、業務固有の `type` に管理下にある安定した HTTPS URI を使うとし、その URI の基点を公開 API の管理ドメインが決まるまで確定しないとしている。
[ADR-016](ADR-016-localize-api-and-spa-messages.md) も、検証の problem type と `errors` の schema を、管理ドメインが決まった時点で追加するとしている。

最初の業務 API の前に、フロントエンドが入力欄ごとの誤りを表示するための `errors` が要る。
管理ドメインはまだ決まっていない。

RFC 9457 は次のように定めている。

- `about:blank` は HTTP status 以上の意味を持たない（§4.2.1）。
- 拡張メンバーは problem type の定義が決める（§3.2）。
- problem type の URI は管理下にあり、変わらないものにする（§4.1）。
- `type` には相対 URI を使ってよく、使う場合はパスを全部書く形（例：`/types/123`）を推奨する（§3.1.1）。
  ただし、相対 URI を正しく扱えない実装があるとも注意している。

SPA とバックエンドは同一オリジンで公開する（[ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)）。

## Decision

- 業務固有の problem type は、パスを全部書いた相対 URI `/problems/<kebab-case>` にする。
  同一オリジンなので、相対 URI は実際のドメインの HTTPS URI に解決され、管理ドメインが決まっても変えない。
- 入力検証エラーの `type` は `/problems/validation-error` とし、status は 400 にする。
  Spring MVC の本文と引数の検証エラー、`ConstraintViolationException` のどれも同じ type にする。
- `errors` は `/problems/validation-error` の拡張メンバーとして定義する。
  各要素は `pointer`（RFC 6901 の JSON Pointer）と `detail`（入力値を含まない説明）を持ち、この type では必ず含む。
- `/problems/validation-error` の `title` は MessageSource の `problem.title.validation-error` を日本語と英語で解決し、`detail` は付けない。
- HTTP status 以上の意味がない problem（401、403、404、405、415、500 など）は `about:blank` のままにする。
  JSON の構文エラーと型の不一致の 400 も `about:blank` にする。
- クライアントは `type` を基底 URI で解決せず、応答の文字列のまま比較する。
  相対 URI を正しく扱えない実装があるため、解決の有無で値が変わらないようにする。
- OpenAPI の `ProblemDetail.type` の format は `uri-reference` にする。
  `BadRequestProblem` は `ValidationProblem`（`ProblemDetail` と `errors`）を参照する。
- `/problems/` の下に SPA の route と API を作らず、説明ページを置くために空けておく。
  説明ページを作るまでは、type の意味、status、対処をこの ADR と OpenAPI の schema の説明に書く。
- この決定は、ADR-013 の「業務固有の問題に管理下にある安定した HTTPS URI を使う」と「カスタム problem type の URI 基点は管理ドメインが決まるまで確定しない」を上書きする。
  ADR-016 の「検証の problem type と `errors` の schema は管理ドメインが確定した時点で追加する」も上書きする。

## Consequences

### Positive

- 管理ドメインを待たずに、入力検証エラーを RFC 9457 に沿った業務固有の type と拡張メンバーで返せる。
- 管理ドメインが決まっても `type` の値が変わらず、`type` で分岐するクライアントに互換でない変更が起きない。
- あとで同じパスに説明ページを置ける。

### Negative

- 相対 URI を正しく扱えない汎用のクライアントやツールでは、`type` を誤って解決するおそれがある。
- 別オリジンのクライアントに公開すると、相対 URI がそのクライアントのオリジンで解決され、意味が変わる。
  独立したクライアントへ公開するときは、絶対 URI へ移すかを判断し直す。
- `/problems/` のパスを SPA と API で使えなくなる。

### Neutral

- 相対 URI を受け入れるため、OpenAPI の `ProblemDetail.type` の format を `uri` から `uri-reference` に広げる。
- 既存の `about:blank` の応答は変わらない。

## Alternatives Considered

### about:blank に errors を付ける

- **Description**：管理ドメインが決まるまで `type` を `about:blank` のままにし、`errors` を付ける。
- **Pros**：URI を決めずに済む。
- **Cons**：`about:blank` は status 以上の意味を持たず、拡張は problem type の定義が決めるため、RFC 9457 §3.2 と §4.2.1 に反する。

### 仮の HTTPS URI を使う

- **Description**：`https://api.example.com/problems/validation-error` のような仮の絶対 URI を `type` にする。
- **Pros**：ADR-013 の形に合う。
- **Cons**：管理ドメインが決まると変わるため、RFC 9457 §4.1 に反し、`type` で分岐するクライアントに互換でない変更になる。

### tag URI を使う

- **Description**：RFC 4151 の `tag:` URI を恒久的な識別子にする。
- **Pros**：ドメインが決まっても変えずに済む。
- **Cons**：tag の authority にするドメインかメールアドレスを別に決める必要があり、説明ページへ解決できない。

## References

- [RFC 9457: Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc9457)
- [RFC 6901: JSON Pointer](https://datatracker.ietf.org/doc/html/rfc6901)
- [ADR-013](ADR-013-standardize-http-api-contracts.md)
- [ADR-014](ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-016](ADR-016-localize-api-and-spa-messages.md)
- `backend/src/main/java/com/example/demo/error/presentation/web/ApiProblemDetails.java`
- `backend/src/main/java/com/example/demo/OpenApiConfig.java`
