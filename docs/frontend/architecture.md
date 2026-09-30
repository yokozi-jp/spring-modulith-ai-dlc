---
type: Architecture
title: フロントエンドアーキテクチャ
description: 業務能力を最上位の変更単位にする軽量な Package by feature によるフロントエンドの構造を説明する。ディレクトリ構成、業務機能の境界、依存方向、共有コードの置き場所を決めるときに読む。
tags: [architecture, frontend, react]
---

# フロントエンドアーキテクチャ

業務能力を最上位の変更単位にし、`features/<business-feature>` に画面と利用者操作を置く。
実在しない責務のために空ディレクトリや共通層を先に作らない。
feature 間の内部 import と、共通コードから feature への依存を作らない。

## 方針

フロントエンドは、業務能力を最上位の変更単位にする軽量な Package by feature を採用する。

バックエンドに対応する業務モジュールがある場合は同じ業務語彙を使うが、バックエンド内部の `domain`、`application`、`presentation`、`infrastructure` は複製しない。

画面とAPIがまだ少ない段階では、実在しない責務のために空ディレクトリや共通層を先に作らない。

URLとデータ取得の開始点にはTanStack Router、server stateにはTanStack Query、form stateにはTanStack Form、local UI stateにはReact Hooksを使う。

OpenAPIからOrvalでnative FetchのTanStack Query clientを生成し、手書きのendpoint、request型、response型との重複を避ける。

この方針は [ADR-032](../adr/ADR-032-organize-frontend-by-business-feature.md) で決定している。

## 現在の構成

現在の `src` は、アプリケーションの起動、ルーティング、共通UI、国際化、小さなutilityだけを持つ。

``` text
frontend/src/
├── main.tsx
├── style.css
├── i18n.ts
├── i18n.test.ts
├── routeTree.gen.ts
├── routes/
│   ├── __root.tsx
│   ├── index.tsx
│   └── -index.test.tsx
├── components/
│   └── ui/
│       ├── button.tsx
│       └── button.test.tsx
└── lib/
    ├── utils.ts
    └── utils.test.ts
```

`main.tsx` はcomposition rootであり、TanStack Queryの `QueryClient` とTanStack Routerを生成してProviderを接続する。

`routeTree.gen.ts` はTanStack Router pluginの生成物なので、手で編集しない。

現在は業務画面と業務APIがないため、`features`、`api`、global storeは存在しない。

## 目標のディレクトリ構成

最初の業務機能を追加した後は、必要な範囲で次の構成へ拡張する。

``` text
frontend/src/
├── main.tsx
├── style.css
├── i18n.ts
├── routeTree.gen.ts
│
├── routes/
│   ├── __root.tsx
│   └── <route>.tsx
│
├── features/
│   └── <business-feature>/
│       ├── <Feature>Page.tsx
│       ├── components/        # feature固有componentがある場合だけ追加
│       ├── hooks/             # custom Hookがある場合だけ追加
│       ├── queries.ts         # queryの合成が必要な場合だけ追加
│       ├── schemas.ts         # 手書き入力schemaがある場合だけ追加
│       └── *.test.tsx
│
├── api/
│   ├── generated/
│   │   ├── endpoints/
│   │   │   └── <tag>/
│   │   ├── models/
│   │   │   ├── <tag>/
│   │   │   └── shared/
│   │   └── mocks/             # OrvalのMSW生成を使う場合だけ追加
│   └── <custom-mutator>.ts     # 共通transport要件がある場合だけ追加
│
├── components/
│   └── ui/
│
└── lib/
```

この構成は完成形の雛形ではなく、ファイルの所属を決めるための上限である。

空の `components`、`hooks`、`queries.ts`、`schemas.ts` は作らず、対応するコードが生じた時点で追加する。

`app`、`services`、`stores`、トップレベル `hooks` は禁止しないが、現在はそれらが受け持つ独立した責務がないため追加しない。

## 業務機能の境界

**フロントエンド機能**は、利用者が目的を達成するための業務能力を表す。

対応するバックエンド業務モジュールがある場合は、`features/<business-feature>` とOpenAPI tagに同じ業務語彙を使う。

ただし、この対応は業務能力の対応であり、Java packageやControllerの一対一の写像ではない。

サーバー専用のbatch、外部連携、技術基盤には、利用者向けコードがなければフロントエンド機能を作らない。

反対に、一つの画面が複数のバックエンドモジュールを利用する場合は、画面側の利用者目的を一つのフロントエンド機能として扱う。

feature名には業務で使う名詞を使い、`management`、`common`、`misc` のように所属を判断できない名前を避ける。

別featureの内部ファイルは直接importしない。

二つのfeatureで同じ公開能力が必要になった場合は、所有するfeatureの公開境界を定めるか、業務上独立したfeatureとして切り出す。

安定した公開境界が存在するまでは、feature rootのbarrel fileを作らない。

## 共有コード

複数featureから使うという理由だけで、トップレベルに汎用的な `shared` ディレクトリを作らない。

現在は共有物の責務を `components/ui`、`lib`、`api/generated`、`i18n.ts` で直接表せるため、`shared` を加えても階層が一つ増えるだけである。

共有候補は、二つ以上のfeatureで同じ責務が確認でき、特定featureの業務語彙へ属さず、featureへ逆依存しない場合にだけ移動する。

`shared` を導入する場合は、`shared/ui`、`shared/lib`、`shared/api`、`shared/config` のように下位の責務を限定する。

その場合は既存の `components/ui`、`lib`、`api` と役割を重複させず、どちらか一方へ統一する。

`shared/hooks` と `shared/utils` は責務が広がりやすいため、具体的な用途名を持つfeature非依存コードだけを置く。

利用箇所が一つしかないコードは、将来の再利用を予測して共有領域へ移さない。

## 依存方向

許可する主要な依存方向は次のとおりである。

``` text
main.tsx -> routeTree.gen.ts -> routes
                                  |
                                  +-> features
                                  |
                                  +-> api/generated

features -> api/generated
         -> components/ui -> lib
         -> lib
         -> i18n
```

`main.tsx` はProviderとRouterの組立てだけを担当し、業務処理を持たない。

`routes` はfeatureとloaderに必要なquery optionsを参照できる。

`features` は生成API、共通UI、utility、国際化を参照できる。

`api/generated`、`components/ui`、`lib`、国際化コードからfeatureを参照しない。

feature間の内部importと循環依存を作らない。

規模が増えてレビューだけでは違反を検出しにくくなった場合は、実在する違反例を基にimport制約のlint ruleを追加する。

## 関連資料

- [フロントエンドのルーティングと状態管理](routing-and-state.md)
- [OrvalとAPI境界](api-client-orval.md)
- [フロントエンドのUI、スタイル、国際化](ui-and-i18n.md)
- [フロントエンドのテストと検証](testing.md)
- [ADR-032: Frontendを業務機能単位で構成する](../adr/ADR-032-organize-frontend-by-business-feature.md)
