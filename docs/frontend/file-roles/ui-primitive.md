---
type: Convention
title: ファイルの役割：UI primitive
description: frontend/packages/ui/src に置く UI primitive（shadcn の Base UI 版の部品と cn）の定義、置き場所と命名、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。shadcn の部品を @frontend/ui に追加するとき、部品の variant や公開の形を変えるときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：UI primitive

UI primitive は、feature と業務に依存しない画面の部品であり、`frontend/packages/ui/src/<部品名>.tsx` に shadcn の Base UI 版として置く。
部品は `packages/ui` で `vp exec shadcn add <部品名>` を実行して追加し、アプリは `@frontend/ui/<部品名>` から部品ごとに import する。
class の結合には `src/lib/utils.ts` の `cn` を使う。
採用の理由は [ADR-025](../../adr/ADR-025-adopt-shadcn-base-ui-and-tailwind.md) に、package の分け方は [ADR-092](../../adr/ADR-092-split-frontend-into-vite-plus-workspace.md) に示す。

## 定義

button や dialog のような部品は、業務によらず、どの画面でも同じ操作と見た目を持つ。
**UI primitive** は、その部品を Base UI の primitive と Tailwind CSS の class で実装したものであり、`@frontend/ui` が公開する。
今あるのは、`button.tsx`（`Button` と `buttonVariants`）と `lib/utils.ts`（`cn`）である。

UI primitive は、業務のデータ、API の型、feature 固有の文言を扱わない。
それらを扱う component は、feature の `components/` か[アプリの共有component](shared-component.md)に置く（[UIとスタイル](../ui-and-style.md#uiとスタイル)）。

Base UI を import できるのは `packages/ui` の production source だけである。
アプリは Base UI を直接使わず、UI primitive を通して使う。

テーマの変数と Tailwind の読込は、同じ package の `src/style.css` が持つ。
扱いは [UIとスタイル](../ui-and-style.md#uiとスタイル) と [ダークモード](../dark-mode.md) に従う。

## 置き場所と命名

- 部品は `frontend/packages/ui/src/<部品名>.tsx` に置く。
  ファイル名は shadcn CLI が生成する名前（`button.tsx`）のままにする（[componentの命名と設計](../component-design.md#命名)）。
- `cn` は `src/lib/utils.ts` に置く。
- barrel の `index.ts` を作らない。
- `package.json` の `exports` の `"./*": "./src/*.tsx"` が部品を公開し、`"./*.test": null` がテストファイルを公開しない。
  `lib` か `hooks` に `.ts` のファイルを足したときは、`exports` に `"./lib/utils"` と同じ形の行を足す。
  足さないと、`packages/ui` の中では解決しても、アプリからは import できない。

## 必須の記述

shadcn の registry が生成した形を保つ。
`button.tsx` は次の形である。

- Base UI の primitive を別名で import し（`Button as ButtonPrimitive`）、それを描画する関数 component にする。
- 見た目は `cva` の `variants` と `defaultVariants` にまとめ、`className` と合わせて `cn` で結合する。
- props の型は、primitive の props と `VariantProps<typeof buttonVariants>` の交差にする。
- 描画する要素に `data-slot="<部品名>"` を付ける。
- 部品と variant の関数を名前付きで export する（`export { Button, buttonVariants }`）。
  アプリは `buttonVariants` を button の見た目の link に使う（[UIの書き方](../ui-and-style.md#uiの書き方)）。

native semantics と、Base UI の keyboard 操作、focus 管理、ARIA 属性を、見た目のために削らない。

部品を追加した後は、[shadcnの部品の追加](../ui-and-style.md#shadcnの部品の追加)の確認（整形、registry の依存の宣言、アプリが使わない部品を残さないこと）を行う。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@base-ui/react/*`、`class-variance-authority`、`cn`、React、同じ package の `@frontend/ui/lib/utils`、`packages/ui/package.json` に書いた依存。
- **依存してはいけないもの**：アプリのコード、`@frontend/api`、`@grafana/*`、feature 固有の文言、production source からのテスト用部品（`msw`、`@testing-library/**` など）。

`packages/ui/package.json` にアプリと `@frontend/api` を書かないので、pnpm の厳格な解決がそれらの import を失敗にする。
`frontend/lint/workspace-layout.test.js` が、書いていない package を解決できないことを確かめる。
相対パスで package の外へ出る import は `import/no-relative-parent-imports` が止める（[ファイルの範囲ごとの制限](../lint-config.md#ファイルの範囲ごとの制限)）。

## 最小の例と典型的な例

最小の例は `cn` である（`frontend/packages/ui/src/lib/utils.ts`）。

```ts
export { cn } from "cn";
```

`cn` package は、競合する Tailwind の utility のうち最後のものを残して class を結合する。

典型的な例は `Button` である（`frontend/packages/ui/src/button.tsx`）。
import は次のとおりである。

```tsx
import { Button as ButtonPrimitive } from "@base-ui/react/button";
import { cn } from "@frontend/ui/lib/utils";
import type { VariantProps } from "class-variance-authority";
import { cva } from "class-variance-authority";
```

その後に `cva` で `buttonVariants` を定義し、`variant`（`default`、`outline`、`secondary`、`ghost`、`destructive`、`link`）と `size` の class を並べる。
部品の本体と export は次のとおりである。

```tsx
function Button({
  className,
  variant = "default",
  size = "default",
  ...props
}: ButtonPrimitive.Props & VariantProps<typeof buttonVariants>) {
  return (
    <ButtonPrimitive
      data-slot="button"
      className={cn(buttonVariants({ variant, size, className }))}
      {...props}
    />
  );
}

export { Button, buttonVariants };
```

アプリは `import { Button, buttonVariants } from "@frontend/ui/button"` と書く（`frontend/apps/web/src/components/route-error.tsx`）。

## 対応するテスト

テストは部品と同じディレクトリに置く。
jsdom と Testing Library を使わず、server render の出力と resolve だけを確かめる（[配置と実行環境](../testing.md#配置と実行環境)）。
操作が要る部品を足すときに、DOM の環境を足す。

- `button.test.tsx` は、`renderToStaticMarkup` の出力で、ネイティブの `button` 要素と `data-slot` を確かめる。
- `exports.test.ts` は、`import.meta.resolve` で、部品を公開し、テストファイルを公開しないことを確かめる。
- `lib/utils.test.ts` は、`cn` が競合する class の最後のものを残すことを確かめる。

次は `frontend/packages/ui/src/button.test.tsx` である。

```tsx
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vite-plus/test";

import { Button } from "./button";

describe("Button", () => {
  it("子の文字列を accessible name にした、ネイティブの button 要素を描画する", () => {
    const markup = renderToStaticMarkup(<Button type="button">Save</Button>);

    expect(markup).toContain("<button");
    expect(markup).toContain('data-slot="button"');
    expect(markup).toContain(">Save</button>");
  });
});
```

## アンチパターン

- 業務のデータ、API の型、feature 固有の文言を扱う component を `packages/ui` に置く。
- アプリで `shadcn add` を実行し、アプリの中に部品を生成する。
- アプリから `@base-ui/**` を直接 import する。
- barrel の `index.ts` を作り、部品をまとめて公開する。
- アプリが使わない部品を、先回りして追加する。
- 見た目を整えるために、native の要素を `div` と ARIA で置き換える、または Base UI の ARIA 属性を消す。
- 値から class 名を組み立てる（`` `h-${size}` ``）。
  Tailwind はソースに完全な文字列として現れる class だけを生成するため、組み立てた class には style が当たらない。

## 作成時のチェックリスト

- [ ] 業務のデータ、API の型、feature 固有の文言を扱わない。［自分で点検］
- [ ] `packages/ui` で `vp exec shadcn add <部品名>` を実行して追加する。［自分で点検］
- [ ] 生成したファイルを `task fe-format` で整形する。［Oxfmt で検査：task fe-check］
- [ ] registry の部品が使う依存を `packages/ui/package.json` に書く。［Knip で検査：task fe-knip］
- [ ] アプリが使わない部品と export を残さない。［Knip で検査：task fe-knip］
- [ ] `lib` か `hooks` に `.ts` を足したら、`exports` に行を足す。［自分で点検］
- [ ] barrel の `index.ts` を作らない。［自分で点検］
- [ ] アプリと `@frontend/api` を `package.json` に書かない。［Vitest で検査：lint/workspace-layout.test.js］
- [ ] 相対パスで package の外を import しない。［Oxlint で検査：import/no-relative-parent-imports］
- [ ] production source でテスト用部品を import せず、テレメトリ SDK も import しない。［Oxlint で検査：no-restricted-imports］
- [ ] 部品を関数 component で書く。［Oxlint で検査：func-style、react/prefer-function-component］
- [ ] native semantics と Base UI の ARIA 属性を保つ。［自分で点検］
- [ ] 値から Tailwind の class 名を組み立てない。［Oxlint で検査：tailwind-classes/no-dynamic-class-name（`className` の template literal だけ。`cn` の使い方は自分で点検）］
- [ ] テストを部品と同じディレクトリに置き、server render の出力と resolve で確かめる。［自分で点検］
- [ ] テストファイルで Base UI を直接 import しない。［Oxlint で検査：no-restricted-imports］
