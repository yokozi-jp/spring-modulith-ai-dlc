---
type: Convention
title: APIのリクエストヘッダーとレスポンスヘッダー
description: APIが受け付けるリクエストヘッダー、品質値の扱い、Content-Type、Cache-Control、Server-Timingの付け方を定める規約。APIにヘッダーを追加するとき、応答のキャッシュの扱いを確かめるとき、処理時間をクライアントへ返すときに読む。
tags: [convention, web-api, http, future-arch-guidelines]
---

# APIのリクエストヘッダーとレスポンスヘッダー

認証情報はCookieまたは`Authorization`ヘッダーで送り、URLと本文に入れない。
応答には必ず`Content-Type`を付け、`charset`を付けない。
API応答はキャッシュさせない。

## リクエストヘッダー

- **認証情報**：ブラウザからはセッションCookie、システム間連携では`Authorization`ヘッダーで送る。クエリパラメータとリクエストボディには入れない。認証方式は[APIの認証、セッション、権限](authentication-and-session.md)に従う。
- **Content-Type**：APIが対応するメディアタイプを指定させる。通常は`application/json`であり、PATCHは[HTTPメソッドの使い分け](http-methods.md)に従う。
- **User-Agent**：クライアントが対向システムの場合は、利用実績を把握するため`User-Agent: SystemABC/1.0`の形式で送るよう求めてよい。
- **Accept-Language**：応答の言語の選択は[ADR-016](../adr/ADR-016-localize-api-and-spa-messages.md)と[フロントエンドの国際化](../frontend/i18n.md)に従う。

独自ヘッダーの名前は[Web APIの方式とURLの設計](api-style.md)に従う。

## 品質値

APIはJSONだけを返し、`Accept`の品質値による応答形式の切り替えを実装しない。
応答の圧縮は前段のリバースプロキシやCDNに任せ、`Accept-Encoding`の品質値をアプリケーションで解釈しない。
`TE`と`Want-Digest`は使わない。

## Content-Type

すべての応答に`Content-Type`を付ける。
JSONの応答は`application/json`とし、`text/plain`を使わない。
エラー応答は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従い`application/problem+json`にする。

JSONの文字コードはRFC 8259によりUTF-8に限られるため、`charset`パラメータを付けない。

```text
Content-Type: application/json
```

## Cache-Control

API応答はキャッシュさせない。
業務データは利用者ごとに権限で制御され、共有端末のブラウザやCDNに残すと他の利用者が参照できるためである。
Spring Securityが既定で付けるキャッシュ抑止のヘッダー（`Cache-Control: no-cache, no-store, max-age=0, must-revalidate`）を無効化しない。

## Server-Timing

`Server-Timing`ヘッダーは、処理時間の調整が必要なAPIに絞って付ける。
全APIへ先回りして組み込まない。

```text
Server-Timing: cache;dur=23.4, db;dur=50, app;dur=75.3
```

- 測定項目は意味のあるものに絞る。DBアクセスが複数あれば`db1`、`db2`のように分けてよい。
- 項目名に製品名（RedisやPostgreSQLなど）を使わない。
- 本番環境で返すかは、APIごとにセキュリティの観点で判断する。

## その他のヘッダー

- セキュリティ関連の応答ヘッダーは[ADR-014](../adr/ADR-014-use-same-origin-spa-security-boundary.md)に従う。
- 廃止予定を示す応答ヘッダーは[APIの互換性と廃止](versioning.md)に従う。
- レート制限時の`Retry-After`は[入口とAPIの機能配置](edge-responsibilities.md)に従う。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
