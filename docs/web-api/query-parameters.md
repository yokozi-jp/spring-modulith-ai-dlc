---
type: Convention
title: クエリパラメータ
description: クエリパラメータを使うHTTPメソッド、載せてはいけない値、共通の語彙、Controllerでの受け方、キーワード、ソート、複数キー、取得項目の絞り込みの指定方法を定める規約。検索APIや一覧APIのパラメータを設計するときに読む。
tags: [convention, web-api, http, future-arch-guidelines]
---

# クエリパラメータ

クエリパラメータはGETとHEADの検索条件だけに使い、秘密情報と個人情報を載せない。
共通の語彙として`q`、`sort`、`fields`、`limit`、`cursor`を使う。
ソート条件と複数キーは、一つのパラメータにカンマ区切りで指定する。

## 使うメソッド

クエリパラメータはGETとHEADだけで使う。

- POST、PUT、PATCHでは、値をリクエストボディに入れる。
- DELETEでは、対象をパスパラメータで指定する。ただし楽観ロックのバージョン番号だけは、[更新の競合制御](optimistic-locking.md)に従いクエリパラメータで渡す。

## 載せない値

アクセストークン、セッションID、個人情報をクエリパラメータに載せない。
APIのURLは画面のアドレスバーに出なくても、開発者ツールやアクセスログから読み取れるためである。
URLに載せられない値を検索条件にする場合は、[HTTPメソッドの使い分け](http-methods.md)に従いPOSTで検索する。

## 共通の語彙

- **q**：自由入力の検索キーワード。プルダウンやチェックボックスで選ぶ条件には、条件ごとに別のパラメータを使う。
- **sort**：ソート条件。形式は後述する。
- **fields**：取得する項目の絞り込み。導入の条件は後述する。
- **limit**：最大取得件数。既定値と上限は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従う。
- **cursor**：次のページの位置。形式と扱いはADR-013に従う。

その他のパラメータには、DBの列が表す概念と同じ語を使い、対応付けの手間を減らす。

## Controllerでの受け方

Controllerはクエリパラメータを`@RequestParam`で項目ごとに受け、検索条件のrecordを作る（[クラスの役割：検索条件](../backend/class-roles/search-criteria.md)）。
項目ごとに受けると、必須かどうかと既定値が引数ごとにOpenAPIへ出る。

## 検索キーワード

複数の語を含むキーワード（`新宿駅　南口`など）は、フロントエンドで区切り文字を変換せず、そのままURLエンコードして送る。
語の分割はサーバーで行う。

## ソート

ソート条件は、`項目名:asc`または`項目名:desc`をカンマ区切りで並べる。

```text
sort=status:asc,releasedAt:desc
```

- 項目名には応答JSONの項目名を使う。
- 指定できる値はOpenAPIの`enum`で列挙し、`style: form`と`explode: false`で定義する。
- 昇順と降順を`+`と`-`で表さない。URLでは`+`が空白として扱われるためである。
- 項目名と昇順降順を別のパラメータ（`sort`と`order_by`など）に分けない。二つのパラメータの要素数が一致することをOpenAPIで検証できないためである。

カーソルページングの並び順と一意なtie-breakerはADR-013に従う。

## 複数キーの指定

複数のIDを指定して一括で参照する場合は、一つのパラメータにカンマ区切りで指定する。

```text
ids=k1,k2,k3
```

複合キーで一意になるリソースは、キーの各要素を区切り文字で結合した値をカンマ区切りで並べる。

```text
keys=aaa-8,bbb-121,ccc-32
```

複合キーで参照させる前に、DBでサロゲートキーを採番して一つのキーで特定できないかを検討する。

## 取得項目の絞り込み

`fields`による応答項目の絞り込みは、必要が確認されるまで導入しない。
導入する場合は、効果をgzip圧縮後のペイロードサイズで比較して確認し、機能を必要最小限にする。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
