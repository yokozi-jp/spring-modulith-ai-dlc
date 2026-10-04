---
type: Architecture
title: フロントエンドアーキテクチャ
description: 業務能力を最上位の変更単位にする軽量な Package by feature によるフロントエンドの構造を説明する。ディレクトリ構成、業務機能の境界と検査、依存方向、共有コードとテストの置き場所を決めるときに読む。
tags: [architecture, frontend, react]
---

# フロントエンドアーキテクチャ

業務能力を最上位の変更単位にし、`features/<business-feature>` に画面と利用者操作を置く。
実在しない責務のために空ディレクトリや共通層を先に作らない。
feature から別 feature のファイルを import せず、共通コードから feature へ依存しない。
この境界は Oxlint の `feature-boundaries/no-cross-feature-import` と `no-restricted-*` の override が検査する。

## 方針

フロントエンドは、業務能力を最上位の変更単位にする軽量な Package by feature を採用する。

バックエンドに対応する業務モジュールがある場合は同じ業務語彙を使うが、バックエンド内部の `domain`、`application`、`presentation`、`infrastructure` は複製しない。

画面とAPIがまだ少ない段階では、実在しない責務のために空ディレクトリや共通層を先に作らない。

URLとデータ取得の開始点にはTanStack Router、server stateにはTanStack Query、form stateにはTanStack Form、local UI stateにはReact Hooksを使う。

OpenAPIからOrvalでnative FetchのTanStack Query clientを生成し、手書きのendpoint、request型、response型との重複を避ける。

この方針は [ADR-032](../adr/ADR-032-organize-frontend-by-business-feature.md) で決定している。

## 現在の構成

現在の `src` は、アプリケーションの起動、ルーティング、アプリシェル、routerの既定の状態表示、API clientのmutatorと生成した型、共通UI、国際化、小さなutilityだけを持つ。

``` text
frontend/src/
├── main.tsx
├── router-defaults.ts
├── router-defaults.test.tsx
├── style.css
├── api/
│   ├── api-fetch.ts
│   ├── api-fetch.test.ts
│   └── generated/models/
├── i18n/
│   ├── index.ts
│   ├── index.test.ts
│   ├── i18next.d.ts
│   ├── resolve-locale.ts
│   ├── resolve-locale.test.ts
│   └── locales/
│       ├── ja.json
│       └── en.json
├── routeTree.gen.ts
├── routes/
│   ├── __root.tsx
│   └── _authenticated/
│       ├── route.tsx
│       ├── index.tsx
│       └── -index.test.tsx
├── components/
│   ├── app-shell.tsx
│   ├── route-pending.tsx
│   ├── route-error.tsx
│   ├── route-not-found.tsx
│   └── ui/
│       ├── button.tsx
│       └── button.test.tsx
└── lib/
    ├── utils.ts
    └── utils.test.ts
```

`main.tsx` はcomposition rootであり、TanStack Queryの `QueryClient` とTanStack Routerを生成してProviderを接続する。
`QueryClient` には、`api/api-fetch.ts` の401の処理（`QueryCache` と `MutationCache` の `onError`）と再試行の判定（`defaultOptions.queries.retry`）を渡す。

`router-defaults.ts` はrouterの既定値（pending、error、not foundのcomponentとpreloadの設定）を一つのobjectにまとめ、`main.tsx` とrouteのテストが同じ値でrouterを作る。

`routes/_authenticated/route.tsx` は認証が要る画面のpathless layoutであり、アプリシェルを描画する。

`routeTree.gen.ts` はTanStack Router pluginの生成物なので、生成元のroute fileを変更して再生成し、生成物を手で編集しない。
再生成の手順と検査は[フロントエンドのテストと検証](testing.md#生成物)にある。

現在は業務画面と業務APIがないため、`features` とglobal storeは存在しない。

## 目標のディレクトリ構成

最初の業務機能を追加した後は、必要な範囲で次の構成へ拡張する。

``` text
frontend/src/
├── main.tsx
├── style.css
├── i18n/
├── routeTree.gen.ts
│
├── routes/
│   ├── __root.tsx
│   └── _authenticated/
│       ├── route.tsx
│       └── <route>/           # 形はルーティングと状態管理の規約に従う
│
├── features/
│   └── <business-feature>/
│       ├── <feature>-page.tsx
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
│   ├── app-shell.tsx
│   ├── route-*.tsx
│   └── ui/
│
├── testing/                   # 最初のMSWテストを書くときに追加
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

featureから別featureのファイルは例外なくimportしない。

他のfeatureのdataや表示が要るときは、次の形で書く。

- **data**：`api/generated` の生成query optionsを直接使い、変換は使う側の `select` で書く。
- **区分値の表示名**：catalogのkeyで引く（[componentの命名と設計](component-design.md#区分値の表示)）。
- **複数featureをまたぐ画面**：routeで合成する。
- **他のfeatureのcomponentを自分の画面の内側に置く場合**：routeから `children` かslot propで受け取る。

featureの公開ファイル（`public.ts`やfeature rootのbarrel file）は作らない。

この形で書けない場面が実際に出たときに、所有するfeatureの公開境界を定めるか、業務上独立したfeatureとして切り出すかを決める。

## 境界の検査

feature間の境界は、`frontend/lint/feature-boundaries.js` のOxlint JS plugin `feature-boundaries/no-cross-feature-import` が検査する。

この規則は、import元のファイルのfeature名とimport先の `@/features/<name>` を比べ、異なれば直し方（routeで合成する）とともに報告する。
対象は静的import、`export ... from`、`import()` である。
`../` によるimportは `import/no-relative-parent-imports` が禁じているため、この規則はaliasだけを見る。

共有層、route、Base UI、`fetch`、テスト用部品のimportの制限は、`no-restricted-imports`、`no-restricted-globals`、`no-restricted-properties` のoverrideで書いている。
ファイルの範囲ごとに効く制限は[Lintとテストのリファレンス](../tooling/lint-and-test.md#フロントエンドのlint設定)にある。

jsPluginは、動的な境界規則をこの一本だけと想定している。
アプリやパッケージに分かれたとき、または動的な規則が三本を超えたときは、dependency-cruiserへ移る。
OxlintのJS plugin APIはalphaである。

## 共有コード

アプリシェル（header、layout）と、routerの既定のpending、error、not foundの表示は `components/` の直下に置く。
`components/ui` にはshadcnのprimitiveだけを置く。

複数featureから使うという理由だけで、トップレベルに汎用的な `shared` ディレクトリを作らない。

現在は共有物の責務を `components`、`lib`、`api/generated`、`i18n` で直接表せるため、`shared` を加えても階層が一つ増えるだけである。

共有候補は、二つ以上のfeatureで同じ責務が確認でき、特定featureの業務語彙へ属さず、featureへ逆依存しない場合にだけ移動する。

`shared` を導入する場合は、`shared/ui`、`shared/lib`、`shared/api`、`shared/config` のように下位の責務を限定する。

その場合は既存の `components/ui`、`lib`、`api` と役割を重複させず、どちらか一方へ統一する。

`shared/hooks` と `shared/utils` は責務が広がりやすいため、具体的な用途名を持つfeature非依存コードだけを置く。

利用箇所が一つしかないコードは、将来の再利用を予測して共有領域へ移さない。

## 依存方向

許可する主要な依存方向は次のとおりである。

``` text
main.tsx -> router-defaults.ts -> components
         -> routeTree.gen.ts -> routes
                                  |
                                  +-> features
                                  |
                                  +-> components
                                  |
                                  +-> api/generated

features -> api/generated
         -> components/ui -> lib
         -> lib
         -> i18n

components -> components/ui
           -> i18n
```

`main.tsx` はProviderとRouterの組立てだけを担当し、業務処理を持たない。

`routes` はfeature、アプリシェル、loaderに必要なquery optionsを参照できる。

`features` は生成API、共通UI、utility、国際化を参照できる。

`api`、`components`、`lib`、`i18n` からfeatureとrouteを参照しない。

featureからrouteを参照せず、routeの情報は `getRouteApi` で取る。

feature間のimportと循環依存を作らない。

これらの向きは[境界の検査](#境界の検査)に書いたlintが検査する。

## テストの置き場所

テストは対象のコードと同じディレクトリに置く。
routeの近くのテストは、route候補に含めないようファイル名を `-` で始める。

複数のテストで使う準備のコード（MSWのserver、手書きのhandlerとfixture、共通のrender helper）は `src/testing/` に置く。
`src/testing/` は、準備のコードを2つ目のテストファイルで使うときに作る。
`src/testing/` はテスト用部品のimport制限の例外であり、coverageの計測対象から外している。

## 関連資料

- [フロントエンドのルーティングと状態管理](routing-and-state.md)
- [OrvalとAPI境界](api-client-orval.md)
- [フロントエンドのUIとスタイル](ui-and-style.md)
- [フロントエンドの国際化](i18n.md)
- [フロントエンドのテストと検証](testing.md)
- [ADR-032: Frontendを業務機能単位で構成する](../adr/ADR-032-organize-frontend-by-business-feature.md)
