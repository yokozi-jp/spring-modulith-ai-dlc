---
type: Convention
title: フロントエンドのUIとスタイル
description: UI primitive と業務 component の置き場所、アクセシビリティ、style の配置を定め、component、style、アクセシビリティを追加または変更するときに読む。
tags: [convention, frontend, ui, accessibility]
---

# フロントエンドのUIとスタイル

feature に依存しない UI primitive だけを `components/ui` に置き、shadcn の Base UI 版と Tailwind CSS で実装する。
native semantics と Base UI のアクセシビリティを維持する。
共通の global style と feature 固有 style の配置を分ける。
button の見た目の link は `buttonVariants` で書き、shadcn の primitive は最初に使う時点で追加する。

## UIとスタイル

featureに依存しないUI primitiveは `components/ui` に置き、shadcnのBase UI版とTailwind CSSで実装する。

業務データ、API型、feature固有の文言を扱うcomponentは `components/ui` に置かない。

共有primitiveを組み合わせた業務componentは、利用するfeatureの `components` に置く。

HTMLの意味と標準操作を優先し、button、label、heading、tableなどのnative semanticsを不要な `div` とARIAで置き換えない。

Base UIが提供するkeyboard操作、focus管理、ARIA属性を維持し、見た目のためにアクセシビリティを削らない。

共通のglobal styleとTailwindの読込は `style.css` に置き、feature固有styleは対象componentの近くへ置く。

## UIの書き方

- **buttonの見た目のlink**：`<Link className={buttonVariants(...)}>` で書く。
  `Button` の `render` にlinkを渡すと、Base UIがroleを `button` に上書きするため使わない。
  `<Link><Button /></Link>` のような入れ子も作らない。
- **空状態、確認dialog、skeleton**：shadcnの `Empty`、`AlertDialog`、`Skeleton` を、最初に使う時点でregistryから `components/ui` へ追加する。
- **読み込み中の表示**：skeletonの装飾は `aria-hidden` にし、読み込み中の文言を一つだけ支援技術に伝える。
- **live region**：状態の変化を読み上げる領域にはlintの推奨どおり `<output>` を使う（`components/route-pending.tsx`）。

## 関連資料

- [ADR-025: shadcn/uiのBase UI版とTailwind CSSを採用する](../adr/ADR-025-adopt-shadcn-base-ui-and-tailwind.md)
- [shadcn/ui: Button（As Link）](https://ui.shadcn.com/docs/components/base/button#as-link)
- [shadcn/ui: Empty](https://ui.shadcn.com/docs/components/base/empty)
