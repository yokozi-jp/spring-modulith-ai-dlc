---
type: ADR
title: 'ADR-138: 外部の利用者が現れる前に API を /api/v1 にし、バージョンの宣言を検査する'
description: 独立した利用者が現れる前に、全業務 API のパスを /api/v1 へ移し、Spring Framework 7 の ApiVersionConfigurer でパスの区間をバージョンとして読む判断と、version の宣言と契約のパスを検査で守る理由。
tags: [adr, backend, api, versioning]
---

# ADR-138: 外部の利用者が現れる前に API を /api/v1 にし、バージョンの宣言を検査する

## Status

Proposed

## Date

2026-11-26

## Context

[ADR-013](ADR-013-standardize-http-api-contracts.md) は、API の利用者が同時配備する SPA だけの間は API のバージョンを導入せず、独立した利用者が現れた時点でパスへ `/api/v1/...` を導入すると決めた。
先に導入すると、実在しない互換性の要件のためにコードと運用が増えるからである。

その後、外部の利用者が現れそうになった。
ADR-013 の Consequences は、バージョンのない API を独立した利用者へ公開すると、あとから version path を足す移行が要ると認めている。
その移行は、利用者が SPA だけの今は、1 つの Pull Request で終わる。
外部の利用者が `/api/orders` を呼び始めたあとでは、旧パスを残す互換の層と、移行の期限の調整が要る。

Spring Framework 7 は API のバージョニングを持つ。
`ApiVersionConfigurer` が、リクエストのバージョンをパスの区間、header、query、メディアタイプから読む。
`@RequestMapping(version = ...)` がメソッドをバージョンに振り分け、`StandardApiVersionDeprecationHandler` が `Deprecation`、`Sunset`、`Link` を付ける。
このリポジトリは Spring Boot 4.2.0、springdoc-openapi 3.2.0 を使う。

試作で次を確かめた。

- `usePathSegment` の述語が `/api/` の外のパスを除いても、`setVersionRequired(false)` がなければ、それらのパスがバージョンの欠落で 400 になる（`/error` で確かめた）。
  `setVersionRequired(false)` を足すと、`/api/` の外のパスはバージョンなしで通る。
- Controller の mapping が `/api/v{version}/memberships`、`version = "1"` のとき、`/api/v1/memberships` が一致する。
  `/api/v2/memberships`、`/api/v0/memberships`、`/api/v1.1/memberships` は 400 になる。
- 対応するバージョンの一覧は、Controller に書いた `version` から決まる。
- `/api/memberships`（バージョンなし）は、パスが mapping に一致せず、これまでと同じ 404 になる。
- springdoc は `{version}` を `version` の値で置き換え、契約のパスを `/api/v1/orders` にする。
- Spring 既定の `SemanticApiVersionParser` は、バージョンの区間の先頭にある数字以外の文字を読み飛ばし、`1.0.0` も受ける。
  `/api/vX1/orders`、`/api/v01/orders`、`/api/v1.0/orders` が v1 の Controller に届いた（PR のレビューで確かめた）。
  パスのテンプレートを `v{version}` と書き、厳格な parser を足すと、これらは 400 になる。
- `usePathSegment` の述語が生の `path.value()` を読むと、`/%61pi/v2/orders` が検査を逃れて v1 の Controller に届く。
  コンテキストパスがあるときは、全リクエストが逃れる。MVC と Spring Security はデコード後の区間で照合するので、述語も同じ区間で読む。
- `version` を書かない mapping は、どのバージョンにも一致する。

## Decision

全業務 API のパスを `/api/v1/...` にし、バージョンの読み取りに `ApiVersionConfigurer` を使う。

- `WebApiVersionConfig` が、`usePathSegment(1, <先頭の区間が api か>)`、`setVersionParser`、`setVersionRequired(false)` を設定する。
  述語はコンテキストパスを除いたデコード後の先頭の区間を読む。
  parser は `v?` と 1 から始まる正の整数だけを受ける（`vX1`、`v01`、`v1.0`、`v0` は 400）。
  対応するバージョンは Controller の宣言から決め、`addSupportedVersions` で別に足さない。
- 業務の Controller は `@RequestMapping(path = "/api/v{version}/<リソース>", version = "1")` と書く。
  セキュリティの `requestMatchers` が使う `PATH` 定数も `/api/v{version}/...` の形にし、どのバージョンにも同じ認可を掛ける。
- SPA、e2e、検証スキルは、同じ Pull Request で `/api/v1/...` へ移す。
  バージョンのない業務のパスは残さない。
  存在しないパスを確かめる `/api/missing` の探り（e2e、ZAP、Taskfile）は、バージョンのないままにする。どの mapping にも一致せず、これまでと同じ 404 を返す。
- 検査を 2 つ足す。
  - `ApiVersionArchTest`（ArchUnit）が、クラスかメソッドの mapping のパスが `/api/` で始まる `@Controller` に、クラスのパスが `/api/v{version}/` で始まることと、正の整数の `version` を求める。
    メソッドの mapping に `/api/` の絶対のパスを書くことも違反にする。
  - Spectral の `path-api-version` が、契約のすべてのパスに `^/api/v[1-9][0-9]*/` を求める。
- OpenAPI の `info.version`（`CONTRACT_VERSION`）を `1.0.0` にする。
  [APIの互換性と廃止](../web-api/versioning.md)が、`/api/v1` を導入するときに `1.0.0` にすると定める。
- `ApiVersionDeprecationHandler` は、廃止するバージョンが現れるまで設定しない。
  最初の廃止のときに `StandardApiVersionDeprecationHandler` を設定する。
- バージョンのない `/api/...` と、対応しないバージョンの扱いは次のとおり。
  - バージョンのない URL は 404 のままにする。
  - 対応しないバージョンは、Spring の 400 を既存の Problem Details の変換に載せる。

## Consequences

### Positive

- 外部の利用者は、最初から `/api/v1/...` を呼べる。
  旧パスを残す互換の層も、移行の期限の調整も要らない。
- v2 は、新しい Controller に `version = "2"` を書くだけで足せる。
  廃止の予告は標準の handler の設定で済む。
- `version` の書き忘れとパスの誤りは、ArchUnit が Controller の段階で、Spectral が契約の段階で止める。

### Negative

- 実在する外部の利用者がいない段階で、バージョンの運用を始める。
  `/api/v1` を呼ぶのは SPA だけの間は、バージョンの利点は使われない。
- パスが変わるため、oasdiff は破壊的変更を検出し、`api-breaking-approved` ラベルが要る。
- `v{version}` の書き方が、Controller と契約のパス、セキュリティの matcher に広がる。
- `http.route`（指標とトレースの route）は、Controller の mapping のとおり `/api/v{version}/orders/{orderId}` になり、バージョンの値を含まない。
  v2 を足したときに指標でバージョンを分けたい場合は、別の判断で属性を足す。
- 対応しないバージョンの 400 は、Spring が作った本文から Problem Details へ変換するので、type は `about:blank` になる。
  バージョン専用の type は決めていない。

### Neutral

- メジャーバージョンの粒度、後方互換の判定、廃止の進め方は、ADR-013 と versioning.md のままである。
- 契約に `/api/v1/` と `/api/v2/` を並べてよい。
  `info.version` の上げ方は変わらない。
- 廃止の応答ヘッダーを付ける設定は、最初の廃止のときに足す。

## Alternatives Considered

### Alternative 1: 独立した利用者が現れるまで導入しない（ADR-013 のまま）

- Description：`/api/...` のまま運用し、利用者が現れた時点で `/api/v1` を導入する。
- Pros：実在しない要件のためのコードと運用がない。
- Cons：導入の時点で、旧パスを使う利用者との移行の調整が要る。
  導入の時期を人の気づきに頼る。

### Alternative 2: Controller のパスに `/api/v1/...` を直接書き、`ApiVersionConfigurer` を使わない

- Description：mapping を `@RequestMapping("/api/v1/orders")` とする。
- Pros：変更が小さく、springdoc の出力の確認も要らない。
- Cons：v2 を足す段階で、`ApiVersionConfigurer` と `version` の宣言へ全 Controller を書き直す。
  対応しないバージョンの拒否と廃止の応答ヘッダーを自作することになる。

### Alternative 3: バージョンを header か query で渡す

- Description：パスは `/api/orders` のままにし、`API-Version` header などで読む。
- Pros：パスが変わらない。
- Cons：ADR-013 が決めた方式と異なる。
  バージョンが URL に見えず、ログ、トレース、`curl` での確認、キャッシュの鍵に現れにくい。

## References

- [ADR-013](ADR-013-standardize-http-api-contracts.md)
- [APIの互換性と廃止](../web-api/versioning.md)
- [Spring Framework 7.0: API Versioning](https://docs.spring.io/spring-framework/reference/7.0/web/webmvc-versioning.html)
- [Spring Framework 7.0: Configuring API Versioning](https://docs.spring.io/spring-framework/reference/7.0/web/webmvc/mvc-config/api-version.html)
- `backend/src/main/java/com/example/demo/WebApiVersionConfig.java`
- `backend/src/test/java/com/example/demo/architecture/ApiVersionArchTest.java`
- `backend/src/test/java/com/example/demo/ApiVersionContractTest.java`
