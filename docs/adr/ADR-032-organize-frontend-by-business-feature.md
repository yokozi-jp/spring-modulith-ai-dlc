---
type: ADR
title: 'ADR-032: Frontendを業務機能単位で構成する'
description: API の所有機能と画面コードの対応を追いやすくするため、Frontend を業務機能単位で構成する決定。
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

## Decision

Frontendは、業務機能を最上位の変更単位にする軽量なfeature-first構成を採用する。
既存の小さな構成は維持し、最初の業務画面または業務APIを実装するときに必要なディレクトリだけを追加する。
目標とする構成は次のとおりである。

``` text
frontend/src/
├── main.tsx
├── routes/
│   ├── __root.tsx
│   └── <route>.tsx
├── features/
│   └── <business-feature>/
│       ├── <Feature>Page.tsx
│       ├── components/        # 必要になった場合だけ追加
│       ├── hooks/             # 必要になった場合だけ追加
│       └── *.test.tsx
├── api/
│   ├── generated/
│   │   ├── endpoints/<tag>/
│   │   ├── models/<tag-or-shared>/
│   │   └── mocks/<tag>/       # MSW生成を有効にした場合だけ追加
│   └── <custom-mutator>.ts     # 共通transport要件が生じた場合だけ追加
├── components/ui/
├── lib/
└── i18n.ts
```

`features/<business-feature>` は利用者に提供する業務能力を表す。
対応するバックエンド業務モジュールがある場合は同じ業務語彙を使うが、サーバー専用モジュールにはFrontend featureを作らない。
一つの利用者操作が複数のバックエンドモジュールを使う場合は、画面側の凝集を優先して一つのFrontend featureから複数のAPIを組み合わせる。

`routes` はURL、path parameter、search parameter、loader、画面componentの接続を担当する。
初期描画に必要なserver stateはroute loaderからTanStack Queryへpreloadし、route fileへ画面実装を蓄積しない。
単一endpointのquery optionsで足りる場合はOrval生成物を直接使い、複数queryの合成や業務上の既定値が必要な場合だけfeature内に手書きのquery定義を置く。

feature内部にはpage、feature固有component、custom Hook、form schemaなど、実際に必要なものだけを置く。
テストは対象ファイルの隣へ置き、種類別のトップレベル `tests` へ分離しない。
featureに依存しないUI primitiveは `components/ui` に置き、小さな純粋関数は `lib` に置く。
共有候補を最初から共通化せず、複数featureで同じ責務が確認できた時点で移す。

依存は `routes` から `features` へ、`features` から `api/generated`、`components/ui`、`lib` へ向ける。
共有ディレクトリからfeatureへ依存させず、別featureの内部ファイルを直接importしない。
複数featureをまたぐ画面の合成はrouteで行い、再利用可能な公開境界が実在するまではfeature barrelを作らない。

React componentはfunction componentとHooksで実装する。
local UI stateにはReactの組み込みHook、server stateにはTanStack Query、URL stateにはTanStack Router、form stateにはTanStack Formを使う。
custom Hookは、再利用するstateful logicまたは外部systemとの同期を、具体的な用途名で表せる場合にfeature内へ置く。
純粋な変換は通常の関数にし、生成Hookをそのまま転送するだけのwrapper Hookやトップレベル `hooks` ディレクトリは作らない。

Orvalは最初の業務APIを追加する変更で設定する。
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
- stateの種類ごとに既存ライブラリを使うため、新しいglobal state依存が要らない。

### Negative

- OpenAPI tagと `operationId` を安定した契約として保守する必要がある。
- 複数featureをまたぐ画面では、routeと各featureの責務を個別に判断する必要がある。
- ディレクトリ規約だけではfeature間の不正なimportを機械的に遮断しないため、規模が増えた場合はlint ruleを追加で判断する必要がある。

### Neutral

- `features`、`api`、feature内部のサブディレクトリは空のまま先行作成しない。
- Orval生成物をGit管理するかどうかは、ADR-024に従って最初の業務APIと生成Taskを追加するときに決める。
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

## References

- [ADR-002: package by feature とオニオンアーキテクチャ](ADR-002-package-by-feature-onion-architecture.md)
- [ADR-024: Frontend API client生成にOrvalを採用する](ADR-024-adopt-orval-for-frontend-api-client.md)
- [ADR-027: Frontendのテスト基盤を標準化する](ADR-027-adopt-frontend-testing-stack.md)
- [React: Reusing Logic with Custom Hooks](https://react.dev/learn/reusing-logic-with-custom-hooks)
- [React: Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks)
- [Orval: React Query](https://orval.dev/docs/guides/react-query/)
- [Orval: Output configuration](https://orval.dev/docs/reference/configuration/output/)
- [TanStack Router: External Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)
- [TanStack Query: Prefer the use of queryOptions](https://tanstack.com/query/latest/docs/eslint/prefer-query-options)
