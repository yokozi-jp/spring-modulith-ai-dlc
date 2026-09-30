# フロントエンドアーキテクチャ

## 方針

フロントエンドは、業務能力を最上位の変更単位にする軽量な Package by feature を採用する。

バックエンドに対応する業務モジュールがある場合は同じ業務語彙を使うが、バックエンド内部の `domain`、`application`、`presentation`、`infrastructure` は複製しない。

画面とAPIがまだ少ない段階では、実在しない責務のために空ディレクトリや共通層を先に作らない。

URLとデータ取得の開始点にはTanStack Router、server stateにはTanStack Query、form stateにはTanStack Form、local UI stateにはReact Hooksを使う。

OpenAPIからOrvalでnative FetchのTanStack Query clientを生成し、手書きのendpoint、request型、response型との重複を避ける。

この方針は [ADR-032](adr/ADR-032-organize-frontend-by-business-feature.md) で決定している。

## 現在の構成

現在の `src` は、アプリケーションの起動、ルーティング、共通UI、国際化、小さなutilityだけを持つ。

```text
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

```text
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

## ルーティング

`routes` はTanStack Routerのfile-based routingを定義する技術境界である。

route fileはpath、path parameter、search parameter、loader、error境界、画面componentの接続を担当する。

画面の表示ロジックと利用者操作は `features/<business-feature>` へ置き、route fileをpage実装の置き場にしない。

初期描画に必要なserver stateはloaderからTanStack Queryへpreloadし、componentのmount後に同じ取得を開始してwaterfallを作らない。

Orvalが生成したquery optionsだけで要件を表せる場合は、それをloaderとcomponentの両方から利用する。

複数queryの合成、業務上の既定値、追加のselect処理が必要な場合だけ、feature内の `queries.ts` または用途を表すcustom Hookへ閉じ込める。

routeの近くへテストを置く場合は、TanStack Routerのroute候補に含めないよう、現在の規約どおりファイル名を `-` で始める。

## OrvalとAPI境界

**生成API境界**は、OpenAPI契約から生成したHTTP client、型、schema、test用mockを置く領域である。

Orvalは、最初の業務APIとGit管理するOpenAPI snapshotを追加する変更で設定する。

初期設定ではTanStack Query client、native Fetch、tag単位の分割を使う。

```typescript
import { defineConfig } from "orval";

export default defineConfig({
  api: {
    input: {
      target: "./openapi/openapi.json",
    },
    output: {
      target: "./src/api/generated/endpoints",
      schemas: {
        path: "./src/api/generated/models",
        splitByTags: true,
      },
      mode: "tags-split",
      client: "react-query",
      httpClient: "fetch",
      clean: true,
    },
  },
});
```

入力ファイルの最終的な配置と生成commandは、ADR-024に従い、最初の業務APIを追加するときに確定する。

各OpenAPI operationには、所有する業務機能のtagを一つ付け、安定した `operationId` を与える。

Orvalは複数tagがあるoperationを先頭tagへ割り当てるため、一つの所有tagに限定すると生成先が記述順へ依存しない。

生成したendpoint、query key、query options、query Hook、request型、response型を正本として使い、同じ型とFetch関数をfeature内へ手書きしない。

Orvalが生成する `models/shared` は複数tagが参照するschemaの生成先であり、手書きの共有コードを置く `shared` ディレクトリではない。

Orvalの `clean` は生成先を削除して再作成できるため、手書きのmutator、MSW server lifecycle、fixtureを `api/generated` に置かない。

MSW handlerをOrvalから生成する場合は生成先だけを `api/generated/mocks` に置き、手書きのserver setupは必要になった時点で `testing/msw` などの生成対象外へ置く。

同一オリジンのsession cookieを使うため、tokenの保存とAuthorization headerの注入は追加しない。

Problem Details、CSRF、timeoutなどの共通transport要件を組み込みFetchだけで表現できないと確認した場合に限り、`api/generated` の外へcustom mutatorを追加する。

API responseをruntimeで検証する必要がある境界では、OpenAPIからOrvalが生成するZod schemaを使い、同じschemaを手書きしない。

## React Hooksと状態

React componentはfunction componentとHooksで実装する。

HooksはReactのstate、context、effectなどをfunction componentから使うための標準機構であり、アプリケーション全体の層を表すものではない。

状態は、その意味に対応する既存機構へ置く。

- **Local UI state**：開閉、選択中のtab、一時的な入力などをReactの `useState` または `useReducer` で扱う。
- **Server state**：API response、loading、error、cache、再取得をTanStack Queryで扱う。
- **URL state**：共有可能な検索条件、page、sorting、filterをTanStack Routerのsearch parameterで扱う。
- **Form state**：入力値、検証、送信状態をTanStack Formで扱う。
- **Derived value**：既存stateから計算できる値はstateへ複製せず、render中または純粋関数で求める。

これらで扱えない共有stateが実在するまでは、ReduxやZustandなどのglobal storeを追加しない。

custom Hookは、再利用するstateful logicまたは外部systemとの同期を、`useMediaQuery` や `usePublicationFilters` のような具体的な用途名で表せる場合に作る。

一回の `useState` を隠すだけのHook、生成Hookをそのまま返すだけのwrapper Hook、`useMount` のようにReact lifecycleを言い換えるHookは作らない。

日付変換、金額計算、responseの整形など、React stateを使わない処理は通常のTypeScript関数にする。

Hookはcomponentまたは別のcustom Hookのトップレベルから呼び、条件分岐、loop、event handler、通常の関数から呼ばない。

React Compilerを有効にしているため、参照同一性が契約になる場合や計測で効果を確認した場合を除き、`useMemo` と `useCallback` を先回りして追加しない。

## 共有コード

複数featureから使うという理由だけで、トップレベルに汎用的な `shared` ディレクトリを作らない。

現在は共有物の責務を `components/ui`、`lib`、`api/generated`、`i18n.ts` で直接表せるため、`shared` を加えても階層が一つ増えるだけである。

共有候補は、二つ以上のfeatureで同じ責務が確認でき、特定featureの業務語彙へ属さず、featureへ逆依存しない場合にだけ移動する。

`shared` を導入する場合は、`shared/ui`、`shared/lib`、`shared/api`、`shared/config` のように下位の責務を限定する。

その場合は既存の `components/ui`、`lib`、`api` と役割を重複させず、どちらか一方へ統一する。

`shared/hooks` と `shared/utils` は責務が広がりやすいため、具体的な用途名を持つfeature非依存コードだけを置く。

利用箇所が一つしかないコードは、将来の再利用を予測して共有領域へ移さない。

## UIとスタイル

featureに依存しないUI primitiveは `components/ui` に置き、shadcnのBase UI版とTailwind CSSで実装する。

業務データ、API型、feature固有の文言を扱うcomponentは `components/ui` に置かない。

共有primitiveを組み合わせた業務componentは、利用するfeatureの `components` に置く。

HTMLの意味と標準操作を優先し、button、label、heading、tableなどのnative semanticsを不要な `div` とARIAで置き換えない。

Base UIが提供するkeyboard操作、focus管理、ARIA属性を維持し、見た目のためにアクセシビリティを削らない。

共通のglobal styleとTailwindの読込は `style.css` に置き、feature固有styleは対象componentの近くへ置く。

## 国際化

利用者へ表示する文言は、現在の `i18n.ts` が提供するlocale解決とmessage catalogを介して取得する。

componentへ同じ表示文言を重複して埋め込まない。

APIのProblem Detailsはバックエンドが解決した利用者向け文言として扱い、フロントエンドの固定文言と混在させない。

message catalogが複数の責務へ分かれる規模になるまでは、空のlocale別ディレクトリへ分割しない。

## 日時表示

APIから受け取る絶対時刻はRFC 3339のUTC表現として受け取り、JavaScriptの `Date` で解析する。

表示には `Intl.DateTimeFormat` を使い、画面要件で地域が決まる場合はIANA time zoneを明示する。

```typescript
const occurredAt = new Date(response.occurredAt);

const label = new Intl.DateTimeFormat("ja-JP", {
  dateStyle: "medium",
  timeStyle: "medium",
  timeZone: "Asia/Tokyo",
}).format(occurredAt);
```

利用者のbrowser設定で表示する場合だけ `timeZone` を省略する。

表示用に整形した文字列を計算またはAPI送信へ再利用しない。

日付だけの値と時刻だけの値は絶対時刻としてUTC変換せず、API契約の文字列表現をその意味のまま扱う。

## 依存方向

許可する主要な依存方向は次のとおりである。

```text
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

## テスト

テストは検証対象のファイルへ併置する。

純粋関数はNode環境で検証し、DOMを必要とするcomponent testだけにjsdomを使う。

利用者操作はTesting Libraryのroleとlabelから対象を見つけ、user-eventでclick、keyboard入力、focusを実行する。

componentの内部state、Hookの呼出回数、CSS classだけを主要な期待値にせず、利用者から見える結果を検証する。

APIを使うcomponent testではOrval生成関数をmodule mockで置き換えず、MSWでHTTP境界を置き換える。

Orvalが生成するMSW handlerで契約を表現できる場合は、それを使って手書きhandlerとの重複を避ける。

生成された `routeTree.gen.ts` とOrval生成物はcoverage対象から除外し、それを利用する手書きコードの挙動を検証する。

route loaderとTanStack Queryを組み合わせた画面では、loading、success、empty、error、再試行のうち、その画面が利用者へ提供する状態を検証する。

## 自動生成と検証

TanStack Router pluginが生成する `routeTree.gen.ts` とOrval生成物は、生成元を変更して再生成する。

生成ファイルへ直接修正を加えない。

最初の業務APIを追加するときは、バックエンドが出力してSpectral検査を通したOpenAPI snapshotからOrvalを実行するTaskを追加する。

同じ変更で、OpenAPI snapshotと生成物のGit管理方針、生成差分の検出方法、coverage除外を確定する。

フロントエンド全体の型検査、lint、test、buildは、ワークスペースルートで次のコマンドから実行する。

```bash
task fe-verify
```

変更範囲を短時間で確認するときは、次のcommandを使う。

```bash
task fe-check
```

## 機能追加時の確認

新しい利用者向け機能を追加するときは、次の順序で配置を決める。

1. 利用者が達成する目的と業務機能名を決める。
2. 対応するバックエンド業務モジュールがある場合は同じ業務語彙を使う。
3. `features/<business-feature>` を作り、最初のpageまたはcomponentを置く。
4. URLとsearch parameterを `routes` に定義し、routeからfeatureのpageを接続する。
5. 初期描画に必要なserver stateがある場合は、TanStack Queryのquery optionsをloaderからpreloadする。
6. OpenAPI operationへ所有featureのtagと安定した `operationId` を付け、Orvalを再生成する。
7. local UI state、server state、URL state、form stateを対応する既存機構へ割り当てる。
8. custom Hookと共通化は、具体的な再利用または外部system同期が存在する場合だけ追加する。
9. 対象コードの隣へ最小のtestを追加し、`task fe-verify` を実行する。

## 関連資料

- [ADR-023: TanStack Form と Zod を採用する](adr/ADR-023-adopt-tanstack-form-and-zod.md)
- [ADR-024: Frontend API client生成にOrvalを採用する](adr/ADR-024-adopt-orval-for-frontend-api-client.md)
- [ADR-025: shadcn/uiのBase UI版とTailwind CSSを採用する](adr/ADR-025-adopt-shadcn-base-ui-and-tailwind.md)
- [ADR-026: OXCでReact Compilerを有効化する](adr/ADR-026-enable-react-compiler-with-oxc.md)
- [ADR-027: Frontendのテスト基盤を標準化する](adr/ADR-027-adopt-frontend-testing-stack.md)
- [ADR-031: Frontendの型検査を厳格化し、tsconfigを正本にする](adr/ADR-031-tighten-frontend-typescript-checks.md)
- [ADR-032: Frontendを業務機能単位で構成する](adr/ADR-032-organize-frontend-by-business-feature.md)
- [React: Reusing Logic with Custom Hooks](https://react.dev/learn/reusing-logic-with-custom-hooks)
- [React: Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks)
- [Orval: React Query](https://orval.dev/docs/guides/react-query/)
- [Orval: Output configuration](https://orval.dev/docs/reference/configuration/output/)
- [TanStack Router: External Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)
- [TanStack Query: Prefer the use of queryOptions](https://tanstack.com/query/latest/docs/eslint/prefer-query-options)
