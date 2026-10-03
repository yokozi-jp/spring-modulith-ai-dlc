---
type: Convention
title: レスポンスボディの形式
description: APIが返すJSONの形、一覧の包み方、値がない項目の表し方、日時、区分値の返し方、区分値の一覧の置き場所を定める規約。APIの応答スキーマを設計するとき、画面のプルダウンなどに使う区分値の扱いを決めるときに読む。
tags: [convention, web-api, json, future-arch-guidelines]
---

# レスポンスボディの形式

JSONの応答は、フロントエンドが加工せずに使える形に整えて返し、一覧は配列をオブジェクトで包む。
値がない項目は`null`を返さず、項目そのものを省く。
区分値はコード値だけを返し、区分値の一覧と表示名はフロントエンドが持つ。

## 応答の形

縦横の変換、粒度の調整、並び順のように画面のために必要な加工は、バックエンドで行ってから返す。
バックエンドで加工するほうが品質を保証しやすいためである。
ただし、特定の画面の要件に合わせてリソースの表現を崩しすぎない。
DBのテーブル構造をそのまま応答にしない。

## 一覧の包み方

一覧は配列をトップレベルにせず、`items`属性に配列を持つオブジェクトで返す。
件数やページングの情報を後から追加できるようにするためである。
ページングの項目と、単一リソースを共通のenvelopeで包まないことは、[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従う。

```json
{
  "items": [
    { "id": 1, "name": "Item 1" },
    { "id": 2, "name": "Item 2" }
  ],
  "nextCursor": "b3BhcXVl"
}
```

## 値がない項目

値が存在しない項目は、`null`を返さず項目そのものを省く。

```json
{ "id": "00001", "name": "Bob" }
```

バックエンドは[application.yaml](../../backend/src/main/resources/application.yaml)の`spring.jackson.default-property-inclusion: non_null`でこの形を出力する。
OpenAPIでは、省略されうる項目を`nullable`ではなく任意項目（`required`に含めない）として定義する。
PATCHで項目を削除するための`null`は[HTTPメソッドの使い分け](http-methods.md)に従う。

## 日時

日時はUNIXタイムスタンプではなく文字列で返す。
絶対時刻の形式と精度は[日時とタイムゾーンの規約](../datetime/timezone-conventions.md)に従う。
精度はマイクロ秒とする。
小数部は`Instant.toString()`と同じく、値に応じて0桁、3桁、6桁のいずれかで出力する。
受け手は小数部の桁数に依存せずに解析する。

## 区分値

区分値は、DBに格納しているコード値だけを返し、表示名を返さない。

```json
{ "orderId": "12345", "orderCategory": "01" }
```

区分値の一覧と表示名はフロントエンドが持ち、区分値を取得するAPIを作らない。
表示名は[フロントエンドの国際化](../frontend/i18n.md)のmessage catalogに置く。
区分値を追加するときは、バックエンドとフロントエンドを同じ変更で更新する。

次の場合は、この方針を見直す。

- **公開API**：コード値に加えて、区分値の名前を返す。
- **モバイルアプリ**：アプリの審査を待たずに区分値を増減できるよう、区分値を取得するAPIを用意する。

## エラー

エラー応答の形式は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従う。
ステータスコードの選択は[HTTPステータスコードの選択](status-codes.md)に従う。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
