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

- **automatic JSX runtimeとReactの書き方**：`react/react-in-jsx-scope`、`react/forbid-component-props`、`react/jsx-max-depth`、`react/jsx-props-no-spreading`をoffにする。
  automatic JSX runtimeと、`className`とprops spreadの書き方と両立しないためである。
- **JSXのファイル**：`react/jsx-filename-extension`で`.jsx`と`.tsx`を許可する。
  既定は`.jsx`だけを許すためである。
- **exportの形**：`import/no-default-export`、`import/no-named-export`、`import/prefer-default-export`、`import/exports-last`、`import/group-exports`をoffにする。
  互いに矛盾し、Vite設定のdefault exportとTanStack Routerのnamed exportを禁じるためである。
- **CSSの読込**：`import/no-unassigned-import`で`**/*.css`を許可する。
  CSSは副作用importで読むためである。
- **modernな構文**：`oxc/no-async-await`、`oxc/no-optional-chaining`、`oxc/no-rest-spread-properties`をoffにする。
  modernなTypeScriptの構文を一律に禁じるためである。
- **型の明示**：`typescript/explicit-function-return-type`、`typescript/explicit-module-boundary-types`、`typescript/prefer-readonly-parameter-types`、`typescript/promise-function-async`をoffにする。
  型推論と役割が重複するためである。
- **`void`**：`no-void`を`allowAsStatement: true`にする。
  event handlerでrejectしないPromiseを捨てる文を書けるようにするためである（後述）。
- **`undefined`**：`no-undefined`をoffにし、`x === undefined`で比較する。
  上書きとshadowingは、有効なままの`no-global-assign`と`no-shadow-restricted-names`が防ぐ。
- **一律のstyle規則**：`capitalized-comments`、`max-lines-per-function`、`no-magic-numbers`、`no-ternary`、`one-var`、`sort-imports`、`sort-keys`をoffにする。
  Oxfmtと役割が重複するか、可読性を下げるためである。
- **関数の書き方**：`func-style`を`"declaration"`にする。
  名前付き関数は関数宣言にし、関数の型注釈から引数を推論させるアロー関数だけを理由付きの行単位の抑制で許す。
- **短い識別子**：`id-length`で`t`だけを例外にする。
  react-i18nextの翻訳関数の慣用名であるためである。
- **ビルド時の定数**：`no-underscore-dangle`で`__TELEMETRY_ENABLED__`と`__TELEMETRY_APP__`だけを許可する。
  `apps/web/vite.config.ts`の`define`が置き換える定数を、ほかの識別子と衝突しない名前にするためである（[ADR-068](../adr/ADR-068-collect-browser-telemetry-with-faro-via-collector.md)）。
- **型のimport**：`no-duplicate-imports`を`allowSeparateTypeImports: true`にし、型のimportを分けて書けるようにする。
- **Vitestの書き方**：`vitest/no-conditional-in-test`、`vitest/no-hooks`、`vitest/no-importing-vitest-globals`、`vitest/prefer-called-times`、`vitest/prefer-describe-function-title`、`vitest/prefer-expect-assertions`、`vitest/prefer-lowercase-title`、`vitest/prefer-strict-boolean-matchers`、`vitest/prefer-to-be-truthy`、`vitest/require-hook`、`vitest/require-test-timeout`をoffにする。
  Vitestの標準APIと競合するか、互いに矛盾するためである。
- **module mock**：`vitest/no-restricted-vi-methods`に`mock`と`doMock`を指定し、`vi.mock`と`vi.doMock`を禁止する。
  module mockはテストを実装の詳細に依存させるため、HTTPはMSW、globalは`vi.spyOn`か`vi.stubGlobal`で置き換える（[フロントエンドのテストと検証](testing.md#書き方)）。
  この規則はrestrictionカテゴリで有効だが、optionを指定しないと何も禁止しない。
- **上限が固定の待ち**：`vitest/no-restricted-vi-methods`に`waitFor`と`waitUntil`も指定し、`vi.waitFor`と`vi.waitUntil`を禁止する。
  どちらも既定の上限の1秒を一括で変えられず、負荷の高いマシンで失敗するからである。
  代わりに、`findBy`か`waitFor`（Testing Library）、`expect.poll`、`createGate`のいずれかを使う。
  `findBy`、`waitFor`、`expect.poll`の上限は設定で一括して決まり、`createGate`は上限を持たない（[フロントエンドのテストと検証](testing.md#書き方)）。
- **Tailwindのclass**：`better-tailwindcss/enforce-canonical-classes`を有効にし、classを正規形にそろえる（`--fix`で直せる）。
  `better-tailwindcss/enforce-shorthand-classes`は役割が重なるため有効にしない。
  jsPluginの`tailwind-classes/no-dynamic-class-name`は、`className`の中で値からclass名を組み立てるテンプレートリテラル（`` `pl-${depth}` ``、`` `${tone}${depth}` ``）を禁じる（[フロントエンドのUIとスタイル](ui-and-style.md#uiの書き方)）。
  objectや配列からclassを選ぶkey（`` tones[`${tone}-strong`] ``）は対象にしない。
  関数の引数に渡すkey（`` tones.get(`${tone}-strong`) ``）は報告するため、classはobjectか配列に並べて選ぶ。
  `className`の外で組み立てた文字列と、`cn()`に渡す変数は検出しない。
- **`lint/**`**：`import/no-nodejs-modules`、`new-cap`、`typescript/no-unsafe-assignment`、`typescript/no-unsafe-call`、`typescript/no-unsafe-member-access`をoffにする。
  Node側のLintツールであり、OxlintのJS plugin APIに型が無いためである。
  `lint/**`はtsconfigの対象外で型の誤りが報告されず、未定義の識別子も止まらないため、Nodeのglobalを許した`no-undef`を有効にする。
  型を使うrule（`typescript/no-unsafe-return`など）はJSDocの型で動くので、`lint/**`のJSDocの型の注釈は消さない。
- **`packages/ui/**`**：shadcnの生成物の書き方に合わせて`shadcn/no-restyle`、`shadcn/no-arbitrary-values`、`shadcn/require-static-classes`をoffにする。
  `react/only-export-components`もoffにする。
  shadcnの生成物は`buttonVariants`のようなvariantをcomponentと同じファイルからexportするためである。
  Base UIをimportできるのはこのpackageだけで、`apps/**`では禁止する。
- **shadcnの部品の認識**：`@shadcn/lint`は、アプリの`components.json`の`aliases.ui`（`@frontend/ui`）と、ルートの`settings.shadcn.componentImports`（`^@frontend/ui(/|$)`）の両方で、`@frontend/ui`からimportした部品を認識する。
  認識した部品の`className`に、部品が持つ見た目のclass（`p-4`など）を渡すと`shadcn/no-restyle`が報告し、`mt-2`のような配置のclassは許す。
- **`apps/web/src/routes/**`**：`react/only-export-components`をoffにする。
  TanStack Routerのroute fileは`Route`をexportするためである。
- **テストファイル**：`react/jsx-no-literals`をoffにし、テストの中の固定文言をcatalogに通さずに書けるようにする。
  `import/max-dependencies`もoffにする。
  テストのimportの数は検証対象に合わせて増え、テスト自身の責務の大きさを表さないからである。

生成物の`apps/*/src/routeTree.gen.ts`と`packages/api/src/generated/**`は、Lint、整形、coverageから外す。
`packages/api/src/generated/**`はjscpdとKnipの対象からも外す。
違反を含むfixtureの`lint/fixtures/**`は、Lint、整形、Knipから外し、ルートのテストの`--exclude`でテストの収集からも外す。

## ファイルの範囲ごとの制限

`no-restricted-imports`、`no-restricted-globals`、`no-restricted-properties`は、overrideごとにoptionが置き換わり、マージされない。
そのため禁止の一覧をルートの`vite.config.ts`の定数に分け、overrideごとに組み合わせている。
同じファイルに複数のoverrideが一致すると、後のoverrideのoptionだけが効くので、overrideの並び順を変えるときは組み合わせ結果を確かめる。

| 対象のファイル                          | `no-restricted-imports`                       | `no-restricted-globals`と`no-restricted-properties` |
| --------------------------------------- | --------------------------------------------- | --------------------------------------------------- |
| すべて（top-level）                     | Base UI、テスト用部品、テレメトリSDK          | HTML sink、network                                  |
| `apps/web/src/{api,components,i18n}/**` | 共有層、Base UI、テスト用部品、テレメトリSDK  | top-levelを引き継ぐ                                 |
| `apps/web/src/lib/**`                   | lib、Base UI、テスト用部品、テレメトリSDK     | top-levelを引き継ぐ                                 |
| `apps/web/src/lib/telemetry.ts`         | lib、Base UI、テスト用部品                    | top-levelを引き継ぐ                                 |
| `apps/web/src/features/**`              | feature、Base UI、テスト用部品、テレメトリSDK | top-levelを引き継ぐ                                 |
| `packages/ui/**`                        | テスト用部品、テレメトリSDK                   | top-levelを引き継ぐ                                 |
| `packages/api/**`                       | Base UI、テスト用部品、テレメトリSDK          | HTML sinkだけ                                       |
| テストファイルと`src/testing/**`        | Base UI、テレメトリSDK                        | top-levelを引き継ぐ                                 |

各禁止の中身は次のとおりである。

- **共有層**：`@/features/**`、`@/routes/**`、`@/routeTree.gen`。
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

組み合わせた結果は、`frontend/lint/lint-config.test.js`が`frontend/lint/fixtures/apps/*/src/**`と`frontend/lint/fixtures/packages/*/src/**`の違反例と正しい例を実際の設定でLintして確かめる。
このテストは、`frontend/lint/lint-project.js`が、ルートの設定と、`apps/web`と`packages/ui`の`vite.config.ts`、`tsconfig.json`、`package.json`、`components.json`、`style.css`、fixtureを一時ディレクトリへ複製して`vp lint`を実行した結果を読む。
fixtureが`@frontend/ui`からimportする部品は、複製せず、実物の`packages/ui`のものを使う。
実物のアプリは`apps/web`だけなので、`apps/*`のoverrideが別のアプリにも効くことは、`apps/web`の設定を複製した`frontend/lint/fixtures/apps/sample`のfixtureで確かめる。
実物の2つ目のアプリができたら、このfixtureを実物に置き換える。
`frontend/lint/tailwind-entry-point.test.js`は、ルート、`apps/web`、`packages/ui`のどこから実行しても、そのpackageのCSSを解決できることを確かめる。

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
`allowTypeAnnotation`は型注釈付きの関数式も許すため、使わない。

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
