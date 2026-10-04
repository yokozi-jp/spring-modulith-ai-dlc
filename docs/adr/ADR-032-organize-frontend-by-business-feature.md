---
type: ADR
title: 'ADR-032: Frontendを業務機能単位で構成する'
description: API の所有機能と画面コードの対応を追いやすくするため、Frontend を業務機能単位で構成し、ファイル名、feature 境界とその検査、route とアプリシェル、状態表示、生成物の保護を定める決定。
tags: [adr, frontend, architecture]
---

# ADR-032: Frontendを業務機能単位で構成する

## Status

Proposed

## Date

2026-09-30

## Context

FrontendはReact 19、TanStack Router、TanStack Queryを採用しているが、現在の画面は一つであり、業務APIもまだ存在しない。
この段階で大規模な階層や空ディレクトリを作ると、実在しない依存関係を先に固定することになる。

一方、バックエンドはADR-002により、業務機能を最上位のパッケージ境界とし、その内部へオニオンアーキテクチャを適用する。
Frontendも業務能力を単位に凝集させれば、APIの所有機能と画面コードの対応を追いやすい。
ただし、バックエンドの `domain`、`application`、`presentation`、`infrastructure` はサーバー内部の依存制御であり、利用者の画面と同じ分割ではない。

また、ADR-024は最初の業務APIからOrvalでnative FetchのTanStack Query clientを生成する方針を定めている。
OrvalはOpenAPI tag単位の分割、query options、query hooks、schema、MSW handlerを生成できる。
生成先は再生成時のclean対象になり得るため、手書きコードと同じディレクトリへ混在させるわけにはいかない。

React HooksはstatefulなUI logicを関数componentから利用する標準機構である。
しかし、custom Hookはディレクトリ分割の単位ではなく、具体的なstateful logicを再利用する単位である。
すべての処理をHookへ変換すると、純粋関数とReactのrendering lifecycleに属する処理の区別が崩れる。

AIエージェントに四つのfeatureを作らせた検証では、feature間のimportが6箇所、feature単位の循環が2組できた。
ディレクトリ規約とレビューだけでは、最初のfeatureから境界が崩れうる。
また、docsはcomponentのファイル名をPascalCaseと定めていたが、Oxlintの `unicorn/filename-case` は既定のkebab-caseを強制しており、docsどおりに作ると `task fe-check` が失敗した。
routerの既定のerror表示は同梱の英語の固定文言のままであり、agentが `routeTree.gen.ts` などの生成物を手で書き換えることを止める仕組みも無かった。

## Decision

Frontendは、業務機能を最上位の変更単位にする軽量なfeature-first構成を採用する。
既存の小さな構成は維持し、最初の業務画面または業務APIを実装するときに必要なディレクトリだけを追加する。
目標とする構成は次のとおりである。

``` text
frontend/src/
├── main.tsx
├── router-defaults.ts
├── routes/
│   ├── __root.tsx
│   └── _authenticated/
│       ├── route.tsx          # 共通のbeforeLoadとアプリシェル
│       └── <route>/
├── features/
│   └── <business-feature>/
│       ├── <feature>-page.tsx
│       ├── components/        # 必要になった場合だけ追加
│       ├── hooks/             # 必要になった場合だけ追加
│       └── *.test.tsx
├── api/
│   ├── generated/
│   │   ├── endpoints/<tag>/
│   │   ├── models/<tag-or-shared>/
│   │   └── mocks/<tag>/       # MSW生成を有効にした場合だけ追加
│   └── <custom-mutator>.ts     # 共通transport要件が生じた場合だけ追加
├── components/
│   ├── app-shell.tsx
│   ├── route-*.tsx            # routerの既定の状態表示
│   └── ui/
├── testing/                   # 最初のMSWテストを書くときに追加
├── lib/
└── i18n/
```

ファイル名とディレクトリ名はkebab-caseにし、component名はPascalCaseのままにする（`OrderListItem` は `order-list-item.tsx`）。
Oxlintの `unicorn/filename-case` の既定とshadcnの生成物がkebab-caseであり、大文字小文字を区別しないファイルシステムでの事故も避けられるため、docsを設定に合わせる。

`features/<business-feature>` は利用者に提供する業務能力を表す。
対応するバックエンド業務モジュールがある場合は同じ業務語彙を使うが、サーバー専用モジュールにはFrontend featureを作らない。
一つの利用者操作が複数のバックエンドモジュールを使う場合は、画面側の凝集を優先して一つのFrontend featureから複数のAPIを組み合わせる。

`routes` はURL、path parameter、search parameter、loader、画面componentの接続を担当する。
初期描画に必要なserver stateはroute loaderからTanStack Queryへpreloadし、route fileへ画面実装を蓄積しない。
単一endpointのquery optionsで足りる場合はOrval生成物を直接使い、複数queryの合成や業務上の既定値が必要な場合だけfeature内に手書きのquery定義を置く。

feature内部にはpage、feature固有component、custom Hook、form schemaなど、実際に必要なものだけを置く。
テストは対象ファイルの隣へ置き、種類別のトップレベル `tests` へ分離しない。
複数のテストで使う準備のコード（MSWのserver、手書きのhandlerとfixture、共通のrender helper）は `src/testing/` に置き、最初のMSWテストを書くときに作る。
featureに依存しないUI primitiveは `components/ui` に置き、小さな純粋関数は `lib` に置く。
`components/ui` はshadcnのprimitiveだけにし、アプリシェル（header、layout）とrouterの既定のpending、error、not foundの表示は `components/` の直下に置く。
共有候補を最初から共通化せず、複数featureで同じ責務が確認できた時点で移す。

依存は `routes` から `features` へ、`features` から `api/generated`、`components/ui`、`lib` へ向ける。
共有層（`api`、`components`、`lib`、`i18n`）からfeatureとrouteへ依存させず、featureからrouteへも依存させない。
featureから別featureのファイルは例外なくimportしない。
他のfeatureのdataは `api/generated` の生成query optionsを直接使い、変換は使う側の `select` で書く。
区分値の表示名はcatalogのkeyで引く。
複数featureをまたぐ画面の合成はrouteで行い、他のfeatureのcomponentを自分の画面の内側に置くときはrouteから `children` かslot propで受け取る。
featureの公開ファイルとfeature barrelは作らず、この形で書けない場面が実際に出たときに、公開境界を定めるかfeatureを切り出すかを決める。

feature間の境界は、`frontend/lint/feature-boundaries.js` のOxlint JS plugin `feature-boundaries/no-cross-feature-import` で検査する。
この規則はimport元のファイルのfeature名とimport先の `@/features/<name>` を比べ、静的import、`export ... from`、`import()` を報告し、報告文に直し方（routeで合成する）を書く。
`../` は既存の `import/no-relative-parent-imports` が禁じているため、aliasだけを見る。
共有層、route、Base UI、`fetch`、テスト用部品のimportの制限は、組み込みの `no-restricted-*` のoverrideで書く（[ADR-034](ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)）。
jsPluginは動的な境界規則をこの一本だけと想定する。
アプリやパッケージに分かれたとき、または動的な規則が三本を超えたときは、dependency-cruiserへ移る。

routeはdirectory形式で書き、一覧、新規、詳細、編集は `index.tsx` と名前付きのrouteで置き、親のlayout routeを作らない。
`_` 接尾辞は、親のlayoutを共有する子のうち一部だけを外す場面に限り、lintでは制限しない。
認証が要る画面はpathless layoutの `_authenticated` の下に置き、共通の `beforeLoad` とアプリシェルをそこに置く。
layout routeにcomponentを持たせるなら `<Outlet />` を描画する。
初期描画のdataはloaderで `ensureQueryData` し、componentは `useSuspenseQuery` で読む。
Loadingはrouterの `defaultPendingComponent`、Errorは `defaultErrorComponent`、存在しないresourceはloaderの `notFound()` と `defaultNotFoundComponent` で受け、画面のcomponentはEmptyとContentだけを分ける。
既定のerror表示はcatalogの文言を使って `error.message` を画面に出さず、再試行はqueryのerrorをresetしてから `router.invalidate()` を呼ぶ。
routerの既定値は `src/router-defaults.ts` にまとめ、`main.tsx` とrouteのテストが同じ値を使う。

生成物（`frontend/src/routeTree.gen.ts`、`frontend/src/api/generated/**`）への書き込みは、KiroのPreToolUse hook `.kiro/hooks/block-generated-writes.json` で止める。
拒否はexit 2とSTDERRの再生成手順で行い、STDINを読めないときと `jq` が無いときは許可する。
hookを通らない書き換えの最終の検出は、`routeTree.gen.ts` を再生成して差分を確かめる `task fe-route-tree-check` に置き、Frontend CIから呼ぶ。

React componentはfunction componentとHooksで実装する。
local UI stateにはReactの組み込みHook、server stateにはTanStack Query、URL stateにはTanStack Router、form stateにはTanStack Formを使う。
custom Hookは、再利用するstateful logicまたは外部systemとの同期を、具体的な用途名で表せる場合にfeature内へ置く。
純粋な変換は通常の関数にし、生成Hookをそのまま転送するだけのwrapper Hookやトップレベル `hooks` ディレクトリは作らない。

Orvalの設定、生成Task、生成物のGit管理は[ADR-051](ADR-051-commit-openapi-contract-and-check-generated-client.md)に従う。
生成clientには `client: 'react-query'`、`httpClient: 'fetch'`、`mode: 'tags-split'` を使い、schemaもtag単位に分割する。
OpenAPI operationには所有する業務機能のtagを一つ付け、安定した `operationId` を与える。
Orvalが所有するclient、model、mockの生成先を `api/generated` の専用サブディレクトリへ限定し、手書きのmutator、MSW lifecycle、fixtureを生成先へ置かない。
共通transport要件が確認できるまではcustom mutatorを追加しない。

## Consequences

### Positive

- Frontendとバックエンドを業務語彙で対応させながら、サーバー内部レイヤーの不要な複製を避けられる。
- 一つの業務変更に必要な画面、component、Hook、testを近くへ置ける。
- routeを薄く保ち、TanStack RouterのloaderとTanStack Queryのcacheを同じquery optionsで連携できる。
- Orval生成物と手書きコードが分離され、安全に再生成できる。
- docsどおりのファイル名でlintを通り、feature間のimportと層の逆向きのimportをlintが直し方とともに報告する。
- 画面ごとにLoadingとErrorの分岐を書かずに済み、状態表示が利用者の言語で統一される。
- agentによる生成物の手書きをhookが止め、hookを通らない `routeTree.gen.ts` の書き換えもCIが検出する。
- stateの種類ごとに既存ライブラリを使うため、新しいglobal state依存が要らない。

### Negative

- OpenAPI tagと `operationId` を安定した契約として保守する必要がある。
- 複数featureをまたぐ画面では、routeと各featureの責務を個別に判断する必要がある。
- feature境界のjsPluginはOxlintのalphaのJS plugin APIに依存し、Oxlintの更新で動作を確かめ直す必要がある。
- jsPluginは動的な境界規則を一本だけと想定しており、アプリやパッケージに分かれたとき、または動的な規則が三本を超えたときはdependency-cruiserへ移る必要がある。
- 他のfeatureのHookやcomponentを直接使えないため、route側の合成とslot propの受け渡しが増える。
- 生成物のpathはhookのscriptにだけあり、Orvalの導入時に見直す必要がある。

### Neutral

- `features`、`api`、feature内部のサブディレクトリは空のまま先行作成しない。
- Orval生成物はGit管理する（[ADR-051](ADR-051-commit-openapi-contract-and-check-generated-client.md)）。
- `app`、`services`、`stores`、トップレベル `hooks` は禁止語ではないが、現在はそれらが解く責務を持たないため追加しない。

## Alternatives Considered

### バックエンドのオニオン層をFrontendへ複製する

- **Description**：各Frontend featureも `domain`、`application`、`presentation`、`infrastructure` に分ける。
- **Pros**：バックエンドと同じ見た目の構成になる。
- **Cons**：Frontendの変更単位と一致せず、API client、UI state、画面componentを不自然なサーバー層へ割り当てる必要がある。

### 技術種別でトップレベルを分割する

- **Description**：`pages`、`components`、`hooks`、`services`、`schemas` をリポジトリ全体で分ける。
- **Pros**：各ファイルの技術的な種類を見つけやすい。
- **Cons**：一つの業務変更が複数ディレクトリへ散らばり、機能間の依存と所有者を追いにくい。

### 完全なFeature-Sliced Designを導入する

- **Description**：`app`、`pages`、`widgets`、`features`、`entities`、`shared` の層と公開API規約を採用する。
- **Pros**：大規模Frontendで依存方向と再利用単位を細かく管理できる。
- **Cons**：業務画面がない現在は分類対象より規約の方が多くなり、既存のTanStack Router構成と重複する層を先に作ることになる。

### Orval生成物を各featureへ配置する

- **Description**：tagごとの生成clientとschemaを対応する `features/<business-feature>` の内部へ出力する。
- **Pros**：API clientと利用画面が物理的に近くなる。
- **Cons**：複数tagで共有するschemaとgeneratorのclean範囲が手書きコードへ入り込み、生成物の所有境界が曖昧になる。

### 所有featureに公開ファイルを一つ置く

- **Description**：`features/<name>/public.ts` だけを他のfeatureからimportできるようにする。
- **Pros**：Hookやcomponentを所有featureから借りられ、routeでの合成が要らない場面が増える。
- **Cons**：公開物が増えるとfeature単位の循環が再び起きうる。
  公開ファイルに置いてよい中身（Hook、component、純粋関数）の規則も別に要る。
  生成query options、catalog、routeの合成で今の要件を書けるため、実際に書けない場面が出るまで作らない。

### featureごとに静的なoverrideを書く

- **Description**：`src/features/<name>/**` ごとに `no-restricted-imports` のoverrideを一つ書き、自分以外のfeatureを禁じる。
- **Pros**：組み込みの規則だけで済み、alphaのJS plugin APIに依存しない。
- **Cons**：featureを足すたびにoverrideを足す必要がある。
  featureのファイルに当たる他の `no-restricted-imports` のoverrideとoptionを共有する必要があり、optionが置き換わるため組合せが崩れやすい。

### eslint-plugin-boundariesを使う

- **Description**：elementとpolicyで許可する依存を宣言するpluginをOxlintの `jsPlugins` で動かす。
- **Pros**：境界の規則を宣言的に増やせる。
- **Cons**：Oxlintは `import/resolver` を既定で有効にしないため、resolverの依存を追加で入れる必要がある。
  規則が一本の今は依存と設定が見合わない。

### SessionStart、Stop、PostToolUseのhookを追加する

- **Description**：sessionの開始に規約を注入する、turnの終わりに検査を走らせる、書き込みの後にOrvalの再生成を促すhookを置く。
- **Pros**：agentが検査やOrvalの再生成を忘れにくくなる。
- **Cons**：SessionStartはblockできず、注入した内容は毎turnの文脈に残る。
  Stopはblockできるかが文書の間で食い違い、連続blockの歯止めもKiroの文書に無い。
  検査はlefthookとCIからTaskfileのTaskを呼ぶ既存の仕組みに任せる。
  Kiroの文書でStopをblockできるかと連続blockの歯止めがそろったとき、またはlefthookを通さない運用が増えたときに再検討する。

## References

- [ADR-002: package by feature とオニオンアーキテクチャ](ADR-002-package-by-feature-onion-architecture.md)
- [ADR-024: Frontend API client生成にOrvalを採用する](ADR-024-adopt-orval-for-frontend-api-client.md)
- [ADR-027: Frontendのテスト基盤を標準化する](ADR-027-adopt-frontend-testing-stack.md)
- [React: Reusing Logic with Custom Hooks](https://react.dev/learn/reusing-logic-with-custom-hooks)
- [React: Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks)
- [Orval: React Query](https://orval.dev/docs/guides/react-query/)
- [Orval: Output configuration](https://orval.dev/docs/reference/configuration/output/)
- [TanStack Router: External Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)
- [TanStack Router: Authenticated Routes](https://tanstack.com/router/latest/docs/framework/react/guide/authenticated-routes)
- [TanStack Router: Not Found Errors](https://tanstack.com/router/latest/docs/framework/react/guide/not-found-errors)
- [TanStack Query: Prefer the use of queryOptions](https://tanstack.com/query/latest/docs/eslint/prefer-query-options)
- [Oxlint: JS Plugins](https://oxc.rs/docs/guide/usage/linter/js-plugins.html)
- [Oxlint: unicorn/filename-case](https://oxc.rs/docs/guide/usage/linter/rules/unicorn/filename-case.html)
- [ADR-034: Frontend ランタイムを固定し Oxlint 全カテゴリを強制する](ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)
