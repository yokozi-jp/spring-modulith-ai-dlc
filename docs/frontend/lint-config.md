---
type: Convention
title: フロントエンドのLint設定
description: Oxlintの規則の例外と調整、ファイルの範囲ごとの制限、Lintを通る書き方を定め、フロントエンドのコードを書くとき、Lintの規則を緩めるか判断するときに読む。
tags: [convention, frontend, lint]
---

# フロントエンドのLint設定

Oxlintの設定の正本は[`frontend/vite.config.ts`](../../frontend/vite.config.ts)の`lint`である。
カテゴリをすべてエラーにし、次の規則だけを無効化または調整する（[ADR-034](../adr/ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)）。
ファイルの範囲ごとの制限はoverrideで効かせ、規則のoptionを変えるときは許す範囲のすぐ外側を拒否するfixtureも置く。

`radix`、`no-new-wrappers`、`typescript/consistent-type-definitions`、`import/no-relative-parent-imports`はカテゴリ指定で有効なので、個別に列挙しない。

OxlintとOxfmtの設定は、workspaceのルートの`frontend/vite.config.ts`の`lint`と`fmt`だけに置き、`apps/web/vite.config.ts`には置かない（[ADR-092](../adr/ADR-092-split-frontend-into-vite-plus-workspace.md)）。
packageの側に`lint`がないため、`vp lint`はルートと`apps/web`のどちらで実行してもルートの設定を使う。
packageの`vite.config.ts`に`lint`か`fmt`があると、`vp lint`をpackageで単体実行したときにルートの規則が黙って外れる。
そのため`frontend/lint/workspace-layout.test.js`が、`apps/*`と`packages/*`のすべての`vite.config.ts`に`lint`と`fmt`がないことを確かめる。
`better-tailwindcss/*`が読むCSSは、packageごとのoverrideが、設定ファイルの場所から作った絶対パスで渡す。
`apps/web/**`は`apps/web/src/style.css`を、`packages/ui/**`は`packages/ui/src/style.css`を指すので、実行する場所によらず同じく解決する。
`apps/web/src/style.css`は`@frontend/ui/style.css`を読み込むため、アプリのCSSからもテーマの変数を解決できる。

## 例外と調整

理由は[ADR-034](../adr/ADR-034-pin-frontend-runtime-and-enforce-oxlint-categories.md)にある。

- **automatic JSX runtimeとReactの書き方**：`react/react-in-jsx-scope`、`react/forbid-component-props`、`react/jsx-max-depth`、`react/jsx-props-no-spreading`をoffにする。
- **JSXのファイル**：`react/jsx-filename-extension`で`.jsx`と`.tsx`を許可する。
- **exportの形**：`import/no-default-export`、`import/no-named-export`、`import/prefer-default-export`、`import/exports-last`、`import/group-exports`をoffにする。
- **CSSの読込**：`import/no-unassigned-import`で`**/*.css`を許可する。
- **modernな構文**：`oxc/no-async-await`、`oxc/no-optional-chaining`、`oxc/no-rest-spread-properties`をoffにする。
- **型の明示**：`typescript/explicit-function-return-type`、`typescript/explicit-module-boundary-types`、`typescript/prefer-readonly-parameter-types`、`typescript/promise-function-async`をoffにする。
- **`void`**：`no-void`を`allowAsStatement: true`にする（後述）。
- **`undefined`**：`no-undefined`をoffにし、`x === undefined`で比較する。
- **一律のstyle規則**：`capitalized-comments`、`max-lines-per-function`、`no-magic-numbers`、`no-ternary`、`one-var`、`sort-imports`、`sort-keys`をoffにする。
- **関数の書き方**：`func-style`を`"declaration"`にする（後述）。
- **短い識別子**：`id-length`で`t`だけを例外にする。
- **ビルド時の定数**：`no-underscore-dangle`で`__TELEMETRY_ENABLED__`と`__TELEMETRY_APP__`だけを許可する。
- **型のimport**：`no-duplicate-imports`を`allowSeparateTypeImports: true`にする。
- **Vitestの書き方**：`vitest/no-conditional-in-test`、`vitest/no-hooks`、`vitest/no-importing-vitest-globals`、`vitest/prefer-called-times`、`vitest/prefer-describe-function-title`、`vitest/prefer-expect-assertions`、`vitest/prefer-lowercase-title`、`vitest/prefer-strict-boolean-matchers`、`vitest/prefer-to-be-truthy`、`vitest/require-hook`、`vitest/require-test-timeout`をoffにする。
- **module mock**：`vitest/no-restricted-vi-methods`に`mock`と`doMock`を指定し、`vi.mock`と`vi.doMock`を禁止する。
  HTTPはMSW、globalは`vi.spyOn`か`vi.stubGlobal`で置き換える（[フロントエンドのテストと検証](testing.md#書き方)）。
- **上限が固定の待ち**：`vitest/no-restricted-vi-methods`に`waitFor`と`waitUntil`も指定し、`vi.waitFor`と`vi.waitUntil`を禁止する。
  代わりに、`findBy`か`waitFor`（Testing Library）、`expect.poll`、`createGate`のいずれかを使う（[フロントエンドのテストと検証](testing.md#書き方)）。
- **Tailwindのclass**：`better-tailwindcss/enforce-canonical-classes`を有効にし、classを正規形にそろえる（`--fix`で直せる）。
  `better-tailwindcss/enforce-shorthand-classes`は有効にしない。
  jsPluginの`tailwind-classes/no-dynamic-class-name`は、`className`の中で値からclass名を組み立てるテンプレートリテラル（`` `pl-${depth}` ``、`` `${tone}${depth}` ``）を禁じる（[フロントエンドのUIとスタイル](ui-and-style.md#uiの書き方)）。
  objectや配列からclassを選ぶkey（`` tones[`${tone}-strong`] ``）は対象にしない。
  関数の引数に渡すkey（`` tones.get(`${tone}-strong`) ``）は報告するため、classはobjectか配列に並べて選ぶ。
  `className`の外で組み立てた文字列と、`cn()`に渡す変数は検出しない。
- **lintを止めるコメント**：jsPluginの`disable-reason/require-disable-reason`は、`oxlint-disable`、`oxlint-disable-next-line`、`oxlint-disable-line`、`eslint-disable`、`eslint-disable-next-line`、`eslint-disable-line`のコメントに、同じ行の`-- <理由>`を求める。
  理由を前の行や、ブロックコメントの次の行に書いても、規則は報告する。
  生成物（`routeTree.gen.ts`と`packages/api/src/generated/**`）は、Lintの対象外なので検査しない。
  `reportUnusedDisableDirectives`は、使われていないコメントを止める別の検査である。
- **`lint/**`**：`import/no-nodejs-modules`、`new-cap`、`typescript/no-unsafe-assignment`、`typescript/no-unsafe-call`、`typescript/no-unsafe-member-access`をoffにし、Nodeのglobalを許した`no-undef`を有効にする。
  `lint/**`のJSDocの型の注釈は消さない。
- **`packages/ui/**`**：`shadcn/no-restyle`、`shadcn/no-arbitrary-values`、`shadcn/require-static-classes`、`react/only-export-components`をoffにする。
  Base UIをimportできるのはこのpackageだけで、`apps/**`では禁止する。
- **shadcnの部品の認識**：`@shadcn/lint`は、アプリの`components.json`の`aliases.ui`（`@frontend/ui`）と、ルートの`settings.shadcn.componentImports`（`^@frontend/ui(/|$)`）の両方で、`@frontend/ui`からimportした部品を認識する。
  認識した部品の`className`に、部品が持つ見た目のclass（`p-4`など）を渡すと`shadcn/no-restyle`が報告し、`mt-2`のような配置のclassは許す。
- **`apps/*/src/routes/**`**：`react/only-export-components`をoffにする。
- **テストファイル**：`react/jsx-no-literals`と`import/max-dependencies`をoffにする。

生成物の`apps/*/src/routeTree.gen.ts`と`packages/api/src/generated/**`は、Lint、整形、coverageから外す。
`packages/api/src/generated/**`はjscpdとKnipの対象からも外す。
違反を含むfixtureの`lint/fixtures/**`は、Lint、整形、Knipから外し、ルートのテストの`--exclude`でテストの収集からも外す。

## ファイルの範囲ごとの制限

`no-restricted-imports`、`no-restricted-globals`、`no-restricted-properties`は、overrideごとにoptionが置き換わり、マージされない。
そのため禁止の一覧をルートの`vite.config.ts`の定数に分け、overrideごとに組み合わせている。
同じファイルに複数のoverrideが一致すると、後のoverrideのoptionだけが効くので、overrideの並び順を変えるときは組み合わせ結果を確かめる。

| 対象のファイル                   | `no-restricted-imports`                                 | `no-restricted-globals`と`no-restricted-properties` |
| -------------------------------- | ------------------------------------------------------- | --------------------------------------------------- |
| すべて（top-level）              | Base UI、テスト用部品、テレメトリSDK                    | HTML sink、network                                  |
| `apps/web/src/{api,i18n}/**`     | 共有層、Base UI、テスト用部品、テレメトリSDK            | top-levelを引き継ぐ                                 |
| `apps/web/src/components/**`     | 共有層、component、Base UI、テスト用部品、テレメトリSDK | top-levelを引き継ぐ                                 |
| `apps/web/src/lib/**`            | lib、Base UI、テスト用部品、テレメトリSDK               | top-levelを引き継ぐ                                 |
| `apps/web/src/lib/telemetry.ts`  | lib、Base UI、テスト用部品                              | top-levelを引き継ぐ                                 |
| `apps/web/src/features/**`       | feature、Base UI、テスト用部品、テレメトリSDK           | top-levelを引き継ぐ                                 |
| `packages/ui/**`                 | テスト用部品、テレメトリSDK                             | top-levelを引き継ぐ                                 |
| `packages/api/**`                | Base UI、テスト用部品、テレメトリSDK                    | HTML sinkだけ                                       |
| テストファイルと`src/testing/**` | Base UI、テレメトリSDK                                  | top-levelを引き継ぐ                                 |

各禁止の中身は次のとおりである。

- **共有層**：`@/features/**`、`@/routes/**`、`@/routeTree.gen`。
- **component**：`@frontend/api/endpoints/**`、`@frontend/api/models`、`@frontend/api/zod/**`。
  `components`が使える`@frontend/api`の入口は`api-fetch`と`csrf`だけである。
- **lib**：共有層の禁止に加えて`@/components/**`。
  `lib`は依存の最下層であり、UIを参照しない。
- **feature**：`@/routes/**`、`@/routeTree.gen`。
  routeの情報は`getRouteApi`で取る。
- **Base UI**：`@base-ui/**`。
  `@frontend/ui`からimportする。`packages/ui/**`のproduction sourceだけが許す。
- **テスト用部品**：`msw`、`msw/**`、`@frontend/api/mocks/**`、`@/testing/**`、`@testing-library/**`。
- **テレメトリSDK**：`@grafana/*`。
  `src/lib/telemetry.ts`を通して呼ぶ（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
  静的importと`import()`の両方を検出する。
- **HTML sink**：globalの`DOMParser`、`innerHTML`などのHTML系property、`document.write`、`document.writeln`。
- **network**：globalの`fetch`と`XMLHttpRequest`、`window.fetch`、`globalThis.fetch`。
  `@frontend/api`の生成clientを使う。`packages/api/**`だけが許可される。

テストファイルでは層のimport制限も外れる。
`packages/ui`のテストファイルもこのoverrideに当たるため、Base UIのimportは禁止のままである。
テストにも層の制限が要るようになったら、dependency-cruiserへ移る。

`apps/web/e2e/**`では、Playwright TestのAPIにvitest pluginの規則が当たるため、`vitest/consistent-test-filename`と`vitest/prefer-importing-vitest-globals`を外す。

feature間のimportは、これとは別にjsPluginの`feature-boundaries/no-cross-feature-import`が`apps/*/src/features/**`のファイルで禁じる（[フロントエンドアーキテクチャ](architecture.md#境界の検査)）。

linkとbuttonの入れ子は、jsPluginの`ui-composition/no-button-in-link`がすべてのファイルで禁じる（[フロントエンドのUIとスタイル](ui-and-style.md#uiの書き方)）。
`Link`か`a`の内側の`Button`か`button`と、`Button`の`render`に渡した`Link`か`a`を報告する。
`render`に関数を渡す場合は、関数が直接返す`Link`か`a`を報告する。
要素は名前だけで判定するので、別名でimportしたcomponentは検出しない。
変数に入れてから返す要素と、条件演算子で返す要素も検出しない。

`useEffect`、`useLayoutEffect`、`useInsertionEffect`、`useEffectEvent`は、jsPluginの`react-rules/no-effect`がすべてのファイルで禁じる（[フロントエンドのルーティングと状態管理](routing-and-state.md#effectの禁止)）。
`react`からの名前付きimportと再export、default importの名前（`React.useEffect`）の参照、分割代入を報告する。
自前の関数で包んだEffectは検出しない。

組み合わせた結果は、`frontend/lint/lint-config.test.js`が`frontend/lint/fixtures/`の違反例と正しい例を実際の設定でLintして確かめる。

`../../apps/web/src/...`のように相対パスでpackageの外へ出るimportは、pnpmの解決を通らないので、`package.json`に依存を書かないことでは止まらない（[フロントエンドアーキテクチャ](architecture.md#現在の構成)）。
このimportは`import/no-relative-parent-imports`が止める。
`ui`からアプリ、`api`から`ui`、アプリから別のアプリへの相対パスのimportを、`lint-config.test.js`がfixtureで確かめる。

規則のoptionを追加または変更するときは、optionが許す範囲のすぐ外側の書き方を拒否するfixtureも置く。
optionの名前から許す範囲を推測せず、規則の文書とfixtureで確かめる。
jsPluginやoverrideの挙動を試すときも、一時ディレクトリへ設定を手で複製せず、fixtureを足して`frontend`で`vp test run --dir lint lint-config`を実行する。

## Lintを通る書き方

名前を付ける関数は、トップレベルでもcomponentやHookの中でも関数宣言で書く。
その場で渡すコールバックはアロー関数で書く（`prefer-arrow-callback`）。
関数の型注釈から引数を推論させる必要がある場合だけ、理由を付けて`func-style`を行単位で抑制し、アロー関数を変数に代入してよい。
`allowTypeAnnotation`は使わない。

```tsx
function OrderRow({ order }: OrderRowProps) {
  function handleSelect() {
    onSelect(order.id);
  }
  return <Button onClick={handleSelect}>{order.code}</Button>;
}

// oxlint-disable-next-line func-style -- ChangeEventHandlerからeventの型を推論させる。
const onChange: ChangeEventHandler<HTMLInputElement> = (event) => {
  setCode(event.target.value);
};
```

event handlerからPromiseを返す関数を呼ぶときは、handlerの中で`void`を付けた文にする。

```tsx
<Button
  onClick={() => {
    void queryClient.invalidateQueries({ queryKey: getListOrdersQueryKey() });
  }}
/>
```

`void`を付けてよいのは、次のPromiseに限る。

- rejectしないPromise（`invalidateQueries`、`refetchQueries`、`refetch`）。
- 失敗を内部で処理したPromise。
- `onSubmit`が例外を投げない（`mutate`を使う）場合の`form.handleSubmit()`。

`typescript/no-misused-promises`と`typescript/strict-void-return`は既定のまま有効であり、async関数をJSXのevent handlerに直接渡さない。

`undefined`の判定は`x === undefined`と`x !== undefined`で書く。

`exactOptionalPropertyTypes`の下で、optionalな値を生成されたrequest型へ渡すときは、`&&`形の条件付きspreadで書く。

```ts
const params = { ...(cursor !== undefined && { cursor }) };
```

検査Taskの仕様は[Lintとテストのリファレンス](../tooling/lint-and-test.md#フロントエンド)にある。
