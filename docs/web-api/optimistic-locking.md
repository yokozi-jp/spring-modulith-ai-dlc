---
type: Convention
title: 更新の競合制御
description: APIで楽観ロックを実現する方式、バージョン番号の受け渡し、競合時のステータスコード、親子のテーブルを更新するときのバージョン番号、DELETEでの指定方法を定める規約。同時に更新されうるリソースの更新APIや削除APIを設計するときに読む。
tags: [convention, web-api, concurrency, future-arch-guidelines]
---

# 更新の競合制御

楽観ロックは、リソースのバージョン番号をリクエストボディで受け渡して実現する。
ETagと`If-Match`、`Last-Modified`と`If-Unmodified-Since`は使わない。
競合したら409を返し、DELETEではバージョン番号をクエリパラメータで渡す。

## 前提

バージョン番号には、[PostgreSQLの共通カラム](../database/postgresql-common-columns.md)が業務テーブルに付ける`lock_no`を使う。

## バージョン番号の受け渡し

1. 一覧取得と単件取得の応答に、リソースごとのバージョン番号を含める。
2. クライアントは更新時に、取得したバージョン番号をリクエストボディに含めて送る。
3. サーバーは送られたバージョン番号とDBの値を比較し、一致したときだけ更新してバージョン番号を進める。

この方式は、一覧で取得した複数のリソースをまとめて更新するバッチAPIにも使える。
一件ずつ更新するAPIだけ`If-Match`で実装すると、リソースごとに方式が揺れるため、単件更新のAPIでも同じ方式にする。

## 競合時の応答

バージョン番号が一致しない場合は409を返す。
悲観ロックの取得に失敗した場合も409を返す。
412は`If-Match`などの条件付きリクエストで使うコードであり、この方式では使わない。
エラー応答の形式は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従う。

## 親子のテーブル

親子関係のあるテーブルを一つのリソースとして更新する場合は、親テーブルのバージョン番号を使う。
子テーブルだけを更新する場合も、親子の両方のバージョン番号を進める。
このルールは、その親子のテーブルを更新するすべての機能で守る。

## DELETE

DELETEでは、バージョン番号をクエリパラメータで渡す。

```text
DELETE /items/12345?lockNo=6192
```

バージョン番号がアクセスログに残るため、競合の調査にも使える。

## 最終更新日時による制御

最終更新日時（`Last-Modified`と`If-Unmodified-Since`）による楽観ロックは使わない。
リクエストヘッダーを書き換えれば容易に上書きでき、バッチ更新に対応できず、複数インスタンスの時刻のずれで正しく判定できない場合があるためである。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
