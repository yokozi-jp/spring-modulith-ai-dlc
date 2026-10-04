---
type: Convention
title: フロントエンドのURL設計
description: 画面のURLパス、path parameter、search parameterの構成と命名、URLに含めない値を定める規約。routeを追加するとき、画面間で値を受け渡す方法を決めるときに読む。
tags: [convention, frontend, routing, url, future-arch-guidelines]
---

# フロントエンドのURL設計

画面のURLは操作対象のリソースを複数形の名詞で表し、識別子をpath parameter、条件をsearch parameterで渡す。
pathの語はkebab-case、parameter名はlowerCamelCaseにし、末尾にスラッシュを付けない。
バックエンドへ振り分けるpathを画面に使わず、session IDやtokenをURLに含めない。

## パスの構成

画面のpathは、その画面が操作するリソースを中心に構成する。
以下の `{orderId}` はpath parameterを表す記法である。

- リソースは複数形の名詞で表す（`/orders`）。
- 個々のリソースは識別子で表す（`/orders/{orderId}`）。
- 親子関係は階層で表す（`/customers/{customerId}/orders/{orderId}`）。
- 新規作成、編集、承認などの操作は、リソースのpathの後ろに操作名を一つ付けて表す（`/orders/new`、`/orders/{orderId}/edit`、`/orders/{orderId}/confirm`）。

`search`、`get`、`delete` のような動詞と、`detail` のように情報を足さない語をpathに入れない。
リソース名は、意味が通じる範囲で略さない。
一つの画面に到達するURLは一つにし、同じ内容を別のpathやsearch parameterで表す代替URLを作らない。

TanStack Routerのfile-based routingでは、path parameterを `$orderId` のように書く。
末尾のスラッシュは、TanStack Routerの `trailingSlash` を既定の `never` のまま使って付けない。

## 予約されたパス

[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)により、配信点は `/api/**`、`/oauth2/**`、`/login/**`、`/logout`、`/error` をバックエンドへ振り分ける。
画面のpathにはこれらを使わない。
ログインはSPAのrouteではなく、バックエンドの認証開始URLから始める。
ログアウト後の画面は、ログインを要求しない `/logged-out` とする。
このpathはリソースではなく状態を表す画面であり、リソースを中心にpathを構成する規則の例外とする。

## 命名

- pathの語はkebab-caseにする（`/system-orders`）。
- path parameterとsearch parameterの名前はlowerCamelCaseにする（`orderId`、`productId`）。
- search parameterは `?key=value` の形にし、値だけの `?value` を使わない。

## search parameter

検索条件、filter、sorting、pageのように、共有や再訪に使う条件はsearch parameterに置く。
検索フォームやfilterの値は、検索や適用の操作のたびにsearch parameterへ反映する。
状態の置き場所の全体は[フロントエンドのルーティングと状態管理](routing-and-state.md)に従う。

search parameterはrouteの `validateSearch` で型と既定値を検証する。
componentから `URLSearchParams` で直接読み書きせず、直列化はTanStack Routerの既定に従う。

## URLに含めない値

- session ID、ID token、access tokenを含めない。認証はsession cookieで行う（[ADR-007](../adr/ADR-007-session-based-auth-with-oidc-pkce.md)）。
- tracking code、`location=nearby` や `time=last-week` のように利用者や時点で意味が変わる値、timestampのように変化し続ける値を含めない。URLの寿命が短くなり、同じ画面に複数のURLが生まれるためである。
- URLで見せてはならないデータを含めない。このデータは[ブラウザに保持するデータとキャッシュ](browser-storage-and-cache.md)に従ってmemoryに置く。

## 公開ページ

認証なしで第三者が閲覧する公開ページを作る場合は、意味を持たない文字列をpathに入れず、浅く直感的な階層にする。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
