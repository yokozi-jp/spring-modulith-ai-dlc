---
type: Convention
title: ファイルの役割：feature の module
description: features/<feature>/ 直下に置く、React を使わない型、定数、関数の module（order-status.ts、notice.ts、server-errors.ts）の定義、置き場所と命名、区分値の表示名と失敗の振り分けの書き方、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。画面、component、Hook、route が共有する業務の関数を書くときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：feature の module

feature の module は、feature の画面、component、Hook、route が共有する、React を使わない型、定数、関数を、一つの関心ごとにまとめた `features/<feature>/<関心>.ts` である。
文言が要る関数は `t` を引数で受け、Hook を呼ばない。
Node 環境の unit test で確かめる。

## 定義

React の state を使わない処理を component や Hook に書くと、業務の判定が描画と混ざり、Node のテストで確かめられない。
**feature の module** は、一つの業務機能に属し、React を使わない型、定数、純粋関数を、関心ごとに分けて持つファイルである（[React Hooksと状態](../routing-and-state.md#react-hooksと状態)）。
今の feature の module は、次の三つである。

- **`features/orders/order-status.ts`**：注文の状態の値、状態と商品の販売の状態の表示名、一覧の取得の引数。
- **`features/orders/notice.ts`**：作成と明細の変更の失敗を、通知と入力欄のサーバーの誤りに分ける関数。
- **`features/orders/server-errors.ts`**：入力検証の 400 の `errors` を、入力欄の名前ごとの誤りに振り分ける関数。

feature の直下の `.ts` でも、入力 schema の `schemas.ts` は [feature の入力 schema](feature-schema.md)、TanStack Form の `form.ts` は [feature の form の組み立て](feature-form.md)の役割である。
複数の feature が使う関数は feature の module にせず、`src/lib/` に置く（[共有コード](../architecture.md#共有コード)）。

## 置き場所と命名

- `frontend/apps/web/src/features/<feature>/` の直下に置く。
- ファイル名は、扱う関心を表す名詞の kebab-case にする（`order-status.ts`、`notice.ts`、`server-errors.ts`）。
  `utils` や `helpers` のように、所属を判断できない語を使わない（[命名](../component-design.md#命名)）。
- テストはファイルの隣に `<関心>.test.ts` で置く（`notice.test.ts`）。

## 必須の記述

- 関数は関数宣言で書き、named export にする。
- 文言が要る関数は、i18next の `TFunction` を最初の引数 `t` で受ける（`orderStatusLabel(t, status)`、`formFailure(t, error, descriptions)`）。
- 戻り値は文字列、通知の `Notice`、`ServerFieldErrors` のような data にし、JSX を返さない。
- 区分値の表示名は、catalog の key の対応を `as const satisfies Record<区分値, string>` で書き、対応にない値はコード値をそのまま返す（[区分値の表示](../component-design.md#区分値の表示)）。
- 失敗の判定は、`ApiProblemError` の `status` と Problem Details の `type` で行う。
  `title` と `detail` の文字列で分岐しない（[分岐と文言](../update-conflicts.md#分岐と文言)）。
- Problem Details の本文は Zod で検証しない（[transportとruntime検証](../api-client-orval.md#transportとruntime検証)）。
- loader と画面が同じ query key を作るための引数の組み立ては、この module に置き、両方から呼ぶ（`listOrdersParams`）。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`@frontend/api/models` の型、`@frontend/api/api-error` の `ApiProblemError`、i18next の `TFunction` の型、`@/lib/`、同じ feature の module。
- **依存してはいけないもの**：`react` と `react-i18next`（Hook と JSX）、同じ feature の画面、component、Hook、`@/routes/**` と `@/routeTree.gen`、他の feature、テスト用の部品、テレメトリの SDK、global の `fetch` と `XMLHttpRequest`。

## 最小の例と典型的な例

最小の例は、注文の状態を扱う `features/orders/order-status.ts` である。
生成型の `status` が `string` なので、値の一覧と catalog の key の対応をここに置く。

```ts
import type { ListOrdersParams } from "@frontend/api/models";
import type { TFunction } from "i18next";

export const orderStatuses = ["DRAFT", "CONFIRMED", "CANCELLED"] as const;
export type OrderStatus = (typeof orderStatuses)[number];

// 生成型の status は string なので、表示名の catalog の key をここで対応づける。
const orderStatusLabels = {
  DRAFT: "orders.status.draft",
  CONFIRMED: "orders.status.confirmed",
  CANCELLED: "orders.status.cancelled",
} as const satisfies Record<OrderStatus, string>;

const salesStatusLabels = {
  ON_SALE: "orders.salesStatus.onSale",
  DISCONTINUED: "orders.salesStatus.discontinued",
} as const satisfies Record<"ON_SALE" | "DISCONTINUED", string>;

export function isOrderStatus(value: string | null | undefined): value is OrderStatus {
  return typeof value === "string" && Object.hasOwn(orderStatusLabels, value);
}

/** 対応にない値はコード値をそのまま、値がなければ catalog の「なし」を返す。 */
export function orderStatusLabel(t: TFunction, status?: string): string {
  if (status === undefined) {
    return t("orders.missingValue");
  }
  return isOrderStatus(status) ? t(orderStatusLabels[status]) : status;
}

export function salesStatusLabel(t: TFunction, status?: string): string {
  if (status === "ON_SALE" || status === "DISCONTINUED") {
    return t(salesStatusLabels[status]);
  }
  return status ?? t("orders.missingValue");
}

/** loader と component が同じ query key を作るよう、一覧の引数をここで組む。 */
export function listOrdersParams(status: OrderStatus | undefined): ListOrdersParams | undefined {
  return status === undefined ? undefined : { status };
}
```

典型的な例は、作成と明細の変更の失敗を振り分ける `features/orders/notice.ts` である。
入力検証の 400 は入力欄の誤りと通知の詳細に分け、それ以外は `@/lib/notice` の `failureNotice` に任せる。

```ts
import type { OrderDetailsResponse } from "@frontend/api/models";
import type { TFunction } from "i18next";

import { noServerErrors, serverFieldErrors } from "@/features/orders/server-errors";
import type { ServerFieldErrors } from "@/features/orders/server-errors";
import { failureNotice, problemNotice } from "@/lib/notice";
import type { Notice } from "@/lib/notice";

/** 作成と明細の変更の失敗を、通知と入力欄のサーバーのエラーに分ける。入力は呼び出し側が残す。 */
export function formFailure(
  t: TFunction,
  error: unknown,
  descriptions: { conflict: string | undefined; unprocessable: string },
): { notice: Notice; serverErrors: ServerFieldErrors } {
  const fieldErrors = serverFieldErrors(error);
  if (fieldErrors !== undefined) {
    return {
      notice: {
        ...problemNotice(t, error, t("orders.notice.validation")),
        details: fieldErrors.others,
      },
      serverErrors: fieldErrors,
    };
  }
  return { notice: failureNotice(t, error, descriptions), serverErrors: noServerErrors };
}

/** 明細の変更の 409 の後の通知と、2 つの選択肢を出すか。 */
export function linesConflictOutcome(
  t: TFunction,
  error: unknown,
  latest: OrderDetailsResponse | undefined,
): { notice: Notice; conflict: boolean } {
  if (latest === undefined) {
    return {
      notice: problemNotice(t, error, t("orders.notice.conflictReloadFailed")),
      conflict: false,
    };
  }
  if (latest.status !== "DRAFT") {
    return { notice: problemNotice(t, error, t("orders.notice.notEditable")), conflict: false };
  }
  return { notice: problemNotice(t, error, t("orders.notice.linesConflict")), conflict: true };
}
```

## 対応するテスト

feature の module は、Node 環境の unit test で確かめる（[観点ごとのテスト](../test-strategy.md#観点ごとのテスト)）。
jsdom の指定（`/* @vitest-environment jsdom */`）を書かず、`t` は catalog だけを読んだ `@/testing/catalog-i18n` から使う。
期待する文言は catalog の日本語で書く。

次は `features/orders/notice.test.ts` である（抜粋）。

```ts
import { ApiProblemError } from "@frontend/api/api-error";
import type { ValidationProblem } from "@frontend/api/models";
import { describe, expect, it } from "vite-plus/test";

import { t } from "@/testing/catalog-i18n";

import { formFailure } from "./notice";

const descriptions = { conflict: "競合の説明", unprocessable: "処理できない説明" };
const general = {
  heading: "操作を完了できませんでした",
  description: "時間をおいてもう一度お試しください。",
};

describe("注文の入力の失敗の通知", () => {
  it("入力の誤りは、入力欄ごとのエラーと、入力欄に結びつかない説明に分ける", () => {
    const problem: ValidationProblem = {
      type: "/problems/validation-error",
      title: "Bad Request",
      status: 400,
      errors: [
        { pointer: "/customerOrderCode", detail: "必須です" },
        { pointer: "/unknown", detail: "その他" },
      ],
    };
    const error = new ApiProblemError(400, problem);

    expect(formFailure(t, error, descriptions)).toStrictEqual({
      notice: {
        heading: "Bad Request",
        description: "入力を確かめてください。",
        details: ["その他"],
      },
      serverErrors: { fields: { customerOrderCode: "必須です" }, others: ["その他"] },
    });
  });
```

## アンチパターン

- 業務の判定や表示名の対応を、component や Hook の中に書く。
- module の関数の中で `useTranslation` を呼ぶ。
  Hook を呼ぶ関数になり、条件分岐の中や Node のテストから呼べなくなる。
- 区分値の表示名を、対応表ではなく分岐で書く。
  値を足したときに、catalog の key の足し忘れを型で検出できない。
- `utils.ts` のような名前で、関心の異なる関数を一つのファイルに集める。
- 利用箇所が一つの feature だけの関数を、将来の再利用を見込んで `src/lib/` に移す。
- Problem Details の `title` や `detail` の文字列で分岐する。

## 作成時のチェックリスト

- [ ] `features/<feature>/` の直下に、関心を表す名詞の名前で置く。［自分で点検］
- [ ] ファイル名を kebab-case にする。［Oxlint で検査：unicorn/filename-case］
- [ ] 関数を関数宣言で書く。［Oxlint で検査：func-style］
- [ ] Hook を呼ばず、文言が要る関数は `t` を引数で受ける。［Oxlint で検査：react/rules-of-hooks（`t` の受け方は自分で点検）］
- [ ] `react` と `react-i18next`、同じ feature の画面、component、Hook に依存しない。［自分で点検］
- [ ] 区分値の表示名を `as const satisfies Record<区分値, string>` の対応で書き、対応にない値はコード値を返す。［自分で点検］
- [ ] 失敗の判定を `status` と Problem Details の `type` で行う。［自分で点検］
- [ ] catalog に存在する key だけを `t` に渡す。［型検査で検査：i18next.d.ts の CustomTypeOptions］
- [ ] 他の feature を import しない。［Oxlint で検査：feature-boundaries/no-cross-feature-import］
- [ ] route、テスト用の部品、テレメトリの SDK を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] `../` で親の directory から import しない。［Oxlint で検査：import/no-relative-parent-imports］
- [ ] Node 環境の unit test を隣に置き、`t` を `@/testing/catalog-i18n` から使う。［自分で点検］
