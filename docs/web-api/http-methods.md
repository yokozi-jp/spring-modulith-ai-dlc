---
type: Convention
title: HTTPメソッドの使い分け
description: 参照、作成、更新、削除、検索に使うHTTPメソッド、PATCHの本文形式、更新系の応答本文、冪等性キーの採番を定める規約。APIのエンドポイントにHTTPメソッドを割り当てるとき、更新系の応答や再試行への備えを決めるときに読む。
tags: [convention, web-api, http, future-arch-guidelines]
---

# HTTPメソッドの使い分け

参照はGET、作成はPOST、更新はPUTを基本とし、PATCHは部分更新が必要な場合だけJSON Merge Patchで使う。
GETに本文を付けず、検索条件をURLで表せない場合だけPOSTで検索する。
更新系の応答本文はフロントエンドが必要とする場合だけ返し、DELETEは204を返す。

## 使うメソッド

業務APIでは次のメソッドを使い、CONNECT、OPTIONS、TRACEを業務の操作に割り当てない。

- **GET、HEAD**：参照に使う。安全かつ冪等である。
- **POST**：作成、非同期処理の受付、カスタムメソッド、URLで表せない検索に使う。冪等ではない。
- **PUT**：既存リソースの全体の置き換えに使う。冪等である。
- **PATCH**：部分更新に使う。冪等とは限らない。
- **DELETE**：削除に使う。冪等である。

## 検索にPOSTを使う条件

参照にはGETを使う。
次のいずれかに当てはまる場合に限り、検索条件をリクエストボディに入れてPOSTで検索する。

- 検索条件が入れ子になり、クエリパラメータで表しにくい。
- 一括検索のキーが多く、URLがHTTP実装の推奨上限（8000オクテット）を超えうる。
- 個人情報や秘密情報のように、URLやログに出してはいけない値を条件に含む。

POSTで検索した場合も、成功時のステータスコードはGETと同じ200にする。
GETのリクエストボディに検索条件を入れない。
HTTP実装によって本文が無視または拒否されるためである。

## 作成

リソースの作成にはPOSTを使い、PUTでリソースを新規作成しない。
PUTで作成すると、作成時に払い出すはずの識別子をクライアントが指定する設計になるためである。
識別子を払い出すだけのAPIも作らない。

## 更新

リソースの更新にはPUTを使う。
次の場合に限り、PATCHを使う。

- クライアントがリソースの全量を持たない。
- 大きなテキスト項目を持つなど、通信量を減らす必要がある。

PATCHのリクエストボディはJSON Merge Patch（RFC 7396）にし、`Content-Type: application/merge-patch+json`で送る。
フレームワークの都合で対応できない場合だけ`application/json`も受け付ける。
JSON Merge Patchでは`null`が項目の削除を表す。
応答で値のない項目を`null`ではなく省略で表す規約は、[レスポンスボディ](response-body.md)に従う。

JSON Merge Patchは配列の一部の要素だけを更新できない。
このような部分更新が必要になったら、PATCHにこだわらずPUTのエンドポイントを設ける。

企業ネットワークの中間機器がPATCHを遮断する利用者を対象にする場合に限り、POSTと`X-HTTP-Method-Override: PATCH`で代用する。

## 更新系の応答本文

POST、PUT、PATCHの応答本文にリソースを含めるのは、フロントエンドがその値を使う場合だけにする。
更新後に画面が改めてGETする場合は、リソースを返さない。

DELETEは204を返す。
削除した子リソースの件数のような情報を返すのは、フロントエンドが削除後にその情報を使う場合だけにする。
削除前の確認画面で件数を見せるだけなら、GETで取得できるようにする。

## 再試行への備え

POSTとPATCHも、タイムアウト後の再試行で処理が重複しないよう、一意制約などを使ってできる限り冪等に設計する。
`Idempotency-Key`を要求する操作の範囲、キーの予約、競合時の応答は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従う。
キーはクライアントがUUIDで採番して送り、キーを払い出すAPIを作らない。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
