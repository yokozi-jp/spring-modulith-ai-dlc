---
type: Convention
title: APIの入力検証の配置
description: 入力検証をOpenAPIスキーマ、バックエンドのアプリケーション、クライアントのどこに置くかを定める規約。APIのリクエストに検証を追加するとき、フロントエンドの入力検証との分担を決めるときに読む。
tags: [convention, web-api, validation, openapi, future-arch-guidelines]
---

# APIの入力検証の配置

型、桁、範囲、正規表現、列挙のように単項目で判定できる検証は、OpenAPIスキーマに記述できる形で実装する。
複数項目の組み合わせやDBの参照が必要な検証は、バックエンドのアプリケーションで実装する。
クライアントで検証していても、サーバーで同じ検証を行う。

## スキーマで表す検証

単項目の検証は、リクエストのDTOにBean Validationの制約として書き、springdoc-openapiが生成するOpenAPIスキーマへ反映させる。
スキーマに検証を細かく書くほど、Orvalが生成するクライアントのZod schemaも同じ検証を持つ。
生成するクライアントの扱いは[OrvalとAPI境界](../frontend/api-client-orval.md)に従う。

この層の検証に失敗したら400を返す。

## アプリケーションで行う検証

次の検証は、スキーマで表さずにバックエンドのアプリケーションで行う。

- 複数の項目を組み合わせた検証。
- マスタの存在確認のように、DBを参照しないと判定できない検証。
- 在庫数のように、業務の状態に依存する検証。

業務条件を満たさない入力には422を返す。
ステータスコードの選択は[HTTPステータスコードの選択](status-codes.md)に、エラー応答の形式は[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)に従う。

## クライアントとサーバーの分担

クライアントの検証は、利用者へ即時に誤りを示すためにある。
サーバーの検証は、ブラウザを介さない想定外の入力からシステムを守るためにある。
そのため、クライアントで検証している項目も、サーバーで省略しない。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
