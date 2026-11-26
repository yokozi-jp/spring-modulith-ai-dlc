---
type: Convention
title: APIの互換性と廃止
description: パスへのバージョンの書き方、APIの改修が後方互換かどうかの判定、バージョンの粒度、APIのバージョンとリリース版の関係、OpenAPIのinfo.versionの上げ方、廃止予定の示し方と削除の条件を定める規約。Controllerを作るとき、既存APIの契約を変更するとき、APIを廃止するときに読む。
tags: [convention, web-api, versioning, future-arch-guidelines]
---

# APIの互換性と廃止

APIは後方互換を保って改修し、破壊的変更が避けられず移行期間が必要な場合だけメジャーバージョンを上げる。
APIのバージョンはメジャーバージョンの粒度で管理し、リリース版の番号と連動させない。
廃止はOpenAPIの`deprecated`と`Deprecation`ヘッダーで予告し、利用実績を確認してから削除する。

## 前提

バージョンの導入の方法と廃止時の応答ヘッダーは[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)が定め、導入の時期と実装は[ADR-138](../adr/ADR-138-introduce-api-v1-with-spring-api-versioning.md)が定める。
この文書は、パスへのバージョンの書き方と、改修が後方互換かどうかの判定、廃止の進め方を定める。

## パスへのバージョンの書き方

業務のAPIはすべて`/api/v1/...`で公開する。
`WebApiVersionConfig`が、`/api/`で始まるパスの次の区間をバージョンとして読む。

- Controllerは`@RequestMapping(path = "/api/v{version}/<リソース>", version = "1")`と書く。
  パスの`v`は文字として書く。パスを`{version}`だけにすると、`/api/x1/...`もv1になる。
- `version`を書かないmappingはどのバージョンにも一致するので、必ず書く。`ApiVersionArchTest`が検査する。
- 契約のパスは`/api/v<メジャー番号>/`で始める。springdocが`{version}`を`version`の値で置き換える。Spectralの`path-api-version`が検査する。
- セキュリティの`requestMatchers`が使うパスは、`/api/v{version}/...`の形にして、どのバージョンにも同じ認可を掛ける。
- 対応するバージョンの一覧は、Controllerに書いた`version`から決まる。対応しないバージョン（`/api/v2/...`など）は400になる。
- バージョンのない`/api/...`は、どのmappingにも一致せず404になる。
- v2を足すときは、新しいControllerに`version = "2"`を書く。契約に`/api/v1/`と`/api/v2/`を並べてよい。

## 後方互換の判定

次の改修は後方互換であり、同じ契約へ加える。

- 任意のクエリパラメータを追加する。
- 応答のJSONに項目を追加する。

次の改修は後方互換を壊す。

- リソースのパスを変更する（`/users/123`を`/customers/123`にするなど）。
- 利用できたクエリパラメータを一つ以上廃止する。
- 応答のJSONから項目を削除する、または項目名を変更する。
- 利用者に影響する振る舞いを変更する（同期で作成していたリソースを非同期で作成するなど）。

バグ修正は振る舞いの変更だが、後方互換を壊す改修として扱わない。
クライアントがバグに依存して実装されている場合は、そのクライアントと個別に調整するか、メジャーバージョンを上げる。

## バージョンの粒度

- バージョンはメジャーバージョンだけをパスに含め（`/api/v1/...`）、`/api/v1.1/...`のようなマイナーバージョンを作らない。
- 内部の振る舞いを少し変える程度なら、新しいバージョンを作らず、任意のオプションを追加して切り替えられるようにする。
- OpenAPIのスキーマが大きく変わる場合は、バージョンを上げて分離する。

APIのバージョン（`v1`、`v2`）は、[リリース管理](../repository/release-management.md)が扱うセマンティックバージョニングの番号と一致させず、別のライフサイクルで管理する。

## OpenAPI文書の版

OpenAPIの`info.version`は、パスのバージョンとは別の、契約の版を表すセマンティックバージョニングの番号である。

- 値は`OpenApiConfig.CONTRACT_VERSION`で手で管理し、初期値を`0.1.0`にする。
- 後方互換な追加ではMINORを上げ、後方互換を壊す変更ではMAJORを上げる。
- `/api/v1`の導入で`1.0.0`にした。
- `1.0.0`にするまでは、後方互換を壊す変更でもMINORを上げる。
- APIのバージョンと同じく、リリース版の番号と連動させない。

版の上げ忘れは機械で検出できないため、Pull Requestのチェックリストで確かめる。
決定の理由は[ADR-052](../adr/ADR-052-commit-openapi-contract-and-check-generated-client.md)を参照する。

## 廃止の予告

APIの廃止を決めたら、次を行う。

1. 利用者への影響を調査し、周知と移行時期の調整を行う。
2. OpenAPIの対象operationに`deprecated: true`を設定する。
3. 応答にADR-013が定める`Deprecation`、`Sunset`、`Link`ヘッダーを付ける。`Deprecation`の値はRFC 9745の形式（`@`とUNIX時刻）にする。
   最初の廃止のときに、`WebApiVersionConfig`へSpringの`StandardApiVersionDeprecationHandler`を設定してヘッダーを付ける。

独立したクライアントには、`Deprecation`ヘッダーを受け取ったら警告として記録するよう、あらかじめ求めておく。

## 削除の条件

廃止予定のAPIは、アクセスログやトレースで利用実績がなくなったことを確認してから削除する。

## 出典

- フューチャー株式会社「Web API設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebAPI/web_api_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
