---
type: Convention
title: ファイルの役割：国際化の resource
description: apps/web/src/i18n に置く message catalog（ja.json と en.json）、i18next の instance、message key の型、locale の解決の定義、置き場所と key の構成、必須の記述、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。利用者向けの文言の key を足す、変える、消すとき、i18n のファイルを変えるときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：国際化の resource

国際化の resource は、利用者向けの固定の文言を言語ごとに持つ message catalog と、それを読む i18next の instance、message key の型、locale の解決であり、`apps/web/src/i18n/` に置く。
文言を足すときは `ja.json` と `en.json` に同じ key を同じ変更で足し、component は `useTranslation` が返す `t` で key を引く。
方式の正文は[フロントエンドの国際化](../i18n.md)にあり、判断理由は [ADR-016](../../adr/ADR-016-localize-api-and-spa-messages.md) に示す。

## 定義

画面の文言を component に直接書くと、言語を足せず、同じ意味の文言を言語ごとに揃えられない。
**国際化の resource** は、文言を message key で引けるように言語ごとに集めたファイルと、それを読み込む設定の組である。
変更の大半は catalog への key の追加であり、ほかのファイルは一つずつしかなく、新しく作らない。

- `locales/ja.json`：既定言語の catalog。message key の型の正本
- `locales/en.json`：英語の catalog
- `index.ts`：`createInstance` で instance を作って初期化し、`i18n` として export する。言語が変わると `<html lang>` を変える
- `i18next.d.ts`：`ja.json` から `CustomTypeOptions` の key の型を導く
- `resolve-locale.ts`：`navigator.languages` の候補を優先順に見て `ja` か `en` を選ぶ `resolveLocale`

backend が返す Problem Details の `title` と `detail` は catalog に置かず、そのまま表示する（[Problem Details](../i18n.md#problem-details)）。
日時の表示は[日時とタイムゾーンの規約](../../datetime/timezone-conventions.md)に従う。

## 置き場所と命名

- `frontend/apps/web/src/i18n/` に置く。
  namespace は既定の `translation` だけを使い、catalog を feature ごとのファイルに分けない（[構成](../i18n.md#構成)）。
- 今の catalog の key は、lowerCamelCase の英語を入れ子の object で区切った形である。
- 業務機能の文言は、feature の名前の object の下に置く（`orders.*`、`tenancy.*`）。
  その下は画面か用途で分ける（`orders.list`、`orders.detail`、`orders.form.errors`、`orders.notice`、`tenancy.members`）。
- feature に属さない route、共有の component、`lib` の文言は top-level に置く（`title`、`loading`、`retry`、`logout`、`notice.generalTitle`）。
- 複数形は i18next の plural の接尾辞を key に付ける。
  日本語は `_other` だけを持ち、英語は `_one` と `_other` を持つ（[数値と複数形](../i18n.md#数値と複数形)）。

## 必須の記述

- key を足す、変える、消すときは、`ja.json` と `en.json` を同じ変更で直す。
  `en.json` に足りない key は `index.ts` の `en satisfies typeof ja` が型検査で落とすが、`en.json` にだけある余分な key は落とさない。
- 数値は `{{value, number}}`、金額は `{{value, currency(currency: JPY)}}` の formatting で書き、整形した文字列を interpolation に渡さない。
- 翻訳文に HTML を書かない。
  文言の一部に要素を埋め込むときは react-i18next の `Trans` を使う（[message catalog](../i18n.md#message-catalog)）。
- 区分値の表示名は catalog の key に置き、区分値から key への対応表で引く（[区分値の表示](../component-design.md#区分値の表示)）。
  今のコードは対応表を `as const satisfies Record<…, string>` で書く（`features/orders/order-status.ts`、`features/tenancy/components/members-table.tsx`）。
- `index.ts` は i18next の global instance と `initReactI18next` を使わず、`supportedLngs` に locale を解決させない（[locale解決](../i18n.md#locale解決)）。
- `interpolation.escapeValue` は `false` のままにする。
  React が描画時にエスケープするためである。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`i18next`、同じ `i18n/` のファイル。
  `index.ts` は import の時点で `document` と `navigator` を読む。
- **依存してはいけないもの**：`@/features/**`、`@/routes/**`、`@/routeTree.gen`、テスト用部品（`msw`、`@/testing/**`、`@testing-library/**` など）、Base UI、テレメトリ SDK（[ファイルの範囲ごとの制限](../lint-config.md#ファイルの範囲ごとの制限)）、`i18next-browser-languagedetector`。

`i18n/` を使うのは `main.tsx`、route、feature、`components/`、`src/testing/` である。
`lib/` と feature の純粋関数は、`@/i18n` を import せず、i18next の `TFunction` 型の `t` を引数で受け取る（`lib/notice.ts`、`features/orders/schemas.ts`）。

## 最小の例と典型的な例

最小の例は、注文の作成の画面の見出しである。
`ja.json` と `en.json` の `orders` の object に、同じ key を置く（上が `ja.json`、下が `en.json`）。

```json
"new": {
  "title": "注文の作成",
  "submit": "作成する"
},
```

```json
"new": {
  "title": "Create an order",
  "submit": "Create"
},
```

`features/orders/pages/order-new-page.tsx` は、`useTranslation` の `t` で key を引く（抜粋）。

```tsx
export function OrderNewPage() {
  const { t } = useTranslation();
```

```tsx
      <h1 id="order-new-heading" className="text-2xl font-semibold">
        {t("orders.new.title")}
      </h1>
```

典型的な例は、数値の formatting を持つ入力の誤りの文言と、区分値の表示名である。
`orders.form.errors.quantityRange` は、`min` と `max` を `number` の formatting で表示する（上が `ja.json`、下が `en.json`）。

```json
"quantityRange": "数量は {{min, number}} 以上 {{max, number}} 以下の整数にしてください。"
```

```json
"quantityRange": "Enter a whole number from {{min, number}} to {{max, number}} for the quantity."
```

`features/orders/schemas.ts` は、数値を整形せずに interpolation の値として渡す。

```ts
  const quantityRange = t("orders.form.errors.quantityRange", {
    min: quantityMin,
    max: quantityMax,
  });
```

`features/orders/order-status.ts` は、区分値から key への対応表を `satisfies` で検査し、対応にない値はコード値をそのまま返す。

```ts
// 生成型の status は string なので、表示名の catalog の key をここで対応づける。
const orderStatusLabels = {
  DRAFT: "orders.status.draft",
  CONFIRMED: "orders.status.confirmed",
  CANCELLED: "orders.status.cancelled",
} as const satisfies Record<OrderStatus, string>;
```

## 対応するテスト

`resolve-locale.test.ts` は、Node の unit test で locale の解決の順序を確かめる。
`index.test.ts` は、`index.ts` が `document` を使うため jsdom で、選んだ言語の formatting を確かめる。

```ts
describe("resolveLocale", () => {
  it("完全一致する後続の候補より優先順を優先する", () => {
    expect(resolveLocale(["ja-JP", "en"])).toBe("ja");
  });
});
```

画面の文言は、component test で role と name から確かめる（[フロントエンドのテストと検証](../testing.md#書き方)）。
既定の言語は `ja` であり、英語の表示は `renderRoute` に `locale: "en"` を渡して確かめる（`features/orders/pages/orders-page.test.tsx`）。

```tsx
    await renderRoute("/orders", { locale: "en" });

    await expect(screen.findByText("No matching orders.")).resolves.toBeTruthy();
```

`t` を引数で受け取る純粋関数は、Node の unit test で `@/testing/catalog-i18n` の `t` を渡して確かめる（`lib/notice.test.ts`、`features/orders/form.test.ts`）。
この `t` は `ja.json` だけを読む。
`renderRoute` と `catalog-i18n` は[テストの道具](testing-helper.md)が扱う。

## アンチパターン

- `aria-label` などの属性に、利用者向けの文言を直接書く。
  JSX の子の文字と違い、Lint で止まらない。
- `en.json` に、`ja.json` にない key を残す。
  使われない文言が残り、型検査でも見つからない。
- 数値を `toLocaleString` で文字列にしてから interpolation に渡す。
  言語を切り替えても桁区切りが変わらない。
- 単数と複数を component の条件分岐で選ぶ。
- Problem Details の `type` を catalog の key に写し、backend の `title` を別の文言で出し直す。
- 区分値の表示名を使うために、その区分値を所有する別の feature を import する。

## 作成時のチェックリスト

- [ ] 利用者向けの固定の文言を JSX の子に直接書かない。［Oxlint で検査：react/jsx-no-literals］
- [ ] `aria-label` などの属性の文言も catalog から引く。［自分で点検］
- [ ] `t` に渡す key が `ja.json` にある。［型検査で検査：i18next.d.ts の CustomTypeOptions］
- [ ] `ja.json` の key が `en.json` にもある。［型検査で検査：index.ts の en satisfies typeof ja］
- [ ] `en.json` に、`ja.json` にない key を残さない。［自分で点検］
- [ ] 業務機能の文言は feature の名前の object の下に、feature に属さない文言は top-level に置く。［自分で点検］
- [ ] 数値と金額は i18next の formatting で書き、整形した文字列を渡さない。［自分で点検］
- [ ] 複数形は plural の接尾辞で表し、component で分岐しない。［自分で点検］
- [ ] 翻訳文に HTML を書かず、要素を埋め込むときは `Trans` を使う。［自分で点検］
- [ ] Problem Details の `title` と `detail` を catalog に写さない。［自分で点検］
- [ ] 区分値の表示名は catalog の key に置き、[区分値の表示](../component-design.md#区分値の表示)に従って引く。［自分で点検］
- [ ] `i18n/` から feature、route、`routeTree.gen` を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] 英語の表示を確かめる component test は、`renderRoute` に `locale: "en"` を渡す。［自分で点検］
