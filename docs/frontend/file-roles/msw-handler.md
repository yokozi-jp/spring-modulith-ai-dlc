---
type: Convention
title: ファイルの役割：MSW の handler と fixture
description: src/testing に置く MSW の server と、業務機能の画面のテストが共有する fixture と handler の定義、置き場所と命名、必須の記述、Orval が生成する MSW handler の使い方、依存、例、テスト、アンチパターン、作成時のチェックリストを定める。API を使う画面のテストを書くとき、テストが共有する応答の値や handler を足すときに読む。
tags: [convention, frontend, file-role]
---

# ファイルの役割：MSW の handler と fixture

MSW の server は package ごとに `src/testing/msw.ts` に一つ置き、業務機能の画面のテストが共有する固定の値と handler は `apps/web/src/testing/<feature>.ts` に置く。
応答の値は `@frontend/api/models` の生成型で型を付け、Orval が生成した `get<OperationId>MockHandler` に渡す。
HTTP の境界の置き換えの方針は[フロントエンドのテストと検証](../testing.md#api境界)に、MSW handler の生成は[OrvalとAPI境界](../api-client-orval.md#operationと生成物の扱い)に従う。

## 定義

API を使う component test は、HTTP の境界を MSW で置き換え、Orval の生成関数を module mock で差し替えない。
**MSW の handler と fixture** は、その置き換えのために複数のテストが共有する server、応答の値、handler を足す関数である。
1 つのテストファイルだけが使う handler は、そのテストファイルに書く（`features/orders/pages/orders-page.test.tsx` の `serveOrders`）。

- `apps/web/src/testing/msw.ts`：`apps/web` の全テストが共有する server。選んだテナントの API（`GET /api/v1/current-tenant`）だけを既定で応える
- `packages/api/src/testing/msw.ts`：`@frontend/api` の全テストが共有する server。既定の handler を持たない
- `apps/web/src/testing/orders.ts`：注文の画面のテストが共有する固定の値、応答、handler を足す関数
- `apps/web/src/testing/tenancy.ts`：テナントの画面のテストが共有する固定の値と、テナントを選ぶ前の 403 の応答

今の `orders.ts` は、注文の画面のテストが共有する操作と要素の取得（`saveQuantity`、`notice`、`firstLineRow`、`errorMessageOf`）と、CSRF の Cookie の差し替え（`stubCsrfCookie`）も持つ。

server の起動、handler の初期化、停止は `setup.ts` が行う（[テストの道具](testing-helper.md)）。
`setup.ts` の `afterEach` が `server.resetHandlers()` を呼ぶので、テストが `server.use()` で足した handler はそのテストの中だけで効く。

Orval が生成する MSW handler（`packages/api/src/generated/mocks/<tag>/<tag>.msw.ts`）は、この役割のファイルではなく生成物である。
生成の設定は `frontend/packages/api/orval.config.ts` の `mock` にあり、OpenAPI の example を既定の値にし（`useExamples: true`）、任意の項目にも値を入れる（`override.mock.required: true`）。
生成物は `task api-gen` か `task api-client-gen` で作り直し、手で書き換えない（[生成物のGit管理と再生成](../api-client-orval.md#生成物のgit管理と再生成)）。

## 置き場所と命名

- server は `src/testing/msw.ts` に置き、`server` の名前で export する。
  `apps/web` と `packages/api` は、それぞれ自分の `src/testing/msw.ts` を持つ。
- 業務機能の fixture と handler は `apps/web/src/testing/<feature>.ts` に置き、ファイル名を `features/` の feature の名前と同じにする（`orders.ts`、`tenancy.ts`）。
- 手書きの server、fixture、handler を `src/generated/` に置かない。
  Orval の `clean` が生成先を消して作り直す。
- 生成 handler は `@frontend/api/mocks/<tag>/<tag>.msw` から import する。
  名前は operationId から作られ、`get<OperationId>MockHandler` になる（`getFindOrderByIdMockHandler`）。
- 今の fixture のファイルの名前は、次の形である。
  - **固定の値**：対象の名詞（`products`、`orderId`、`tradingTenant`）。
  - **応答の値を作る関数**：対象の名詞（`draftOrder`、`orderSummary`）。
  - **応答を作る関数**：応答の意味（`problemResponse`、`tenantNotSelected`、`noContent`）。
  - **handler を足す関数**：`serve` で始める（`serveDetail`、`serveDetailFailingRefetch`）。

## 必須の記述

- 応答の値は `@frontend/api/models` の生成型で型を付け、同じ型を手書きしない（`MembershipResponse`、`OrderDetailsResponse`）。
- 値を変えて使う fixture は、既定の値に `overrides: Partial<生成型>` を spread する関数にする（`draftOrder`、`orderSummary`）。
- 生成 handler には、fixture か fixture を返す関数を必ず渡す。
  引数を省くと、OpenAPI の example と faker で作った値が返り、配列の件数が 1 から 10 の乱数になる。
- 要求ごとに応答を変える、または要求を記録するときは、生成 handler に関数を渡す（`getFindOrderByIdMockHandler(() => state.order)`）。
  関数は MSW の resolver の引数を受け取る（`({ request }) => ...`）。
- 生成 handler は、OpenAPI の成功の status（200、201、204 など）を固定で返す。
- 失敗の応答は、backend と同じ `Content-Type: application/problem+json` と Problem Details の本文で返す（`problemResponse`、`tenantNotSelected`）。
  `apiFetch` は、この `Content-Type` のときだけ本文を `problem` に読む（[transportとruntime検証](../api-client-orval.md#transportとruntime検証)）。
- ID は、版と variant の桁が正しい UUID にする（`11111111-1111-4111-8111-111111111111`）。
  生成 Zod の `zod.uuid()` は、この桁が合わない UUID を拒否する。
- 時刻は `Z` 付きのマイクロ秒精度の RFC 3339 にする（`2026-10-05T01:02:03.123456Z`、[日時とタイムゾーンの規約](../../datetime/timezone-conventions.md)）。

## 依存してよいもの、してはいけないもの

- **依存してよいもの**：`msw` の `http` と `HttpResponse`、`msw/node` の `setupServer`（`msw.ts` だけ）、`@frontend/api/mocks/**`、`@frontend/api/models` の型、同じ `src/testing/` のファイル。
  今の `orders.ts` は、画面の操作のために Testing Library、user-event の型、`vi` も使う。
- **依存してはいけないもの**：Base UI、テレメトリ SDK、global の `fetch`、`vi.mock` と `vi.doMock` による module の差し替え。

production source は `msw`、`@frontend/api/mocks/**`、`@/testing/**` を import できない（[ファイルの範囲ごとの制限](../lint-config.md#ファイルの範囲ごとの制限)）。
`packages/api` の `src/testing/` は `package.json` の `exports` に載らないので、アプリから import できない。

## 最小の例と典型的な例

最小の例は、`apps/web` の server の `apps/web/src/testing/msw.ts` である。

```ts
import { getFindCurrentTenantMockHandler } from "@frontend/api/mocks/tenancy/tenancy.msw";
import { setupServer } from "msw/node";

import { tradingTenant } from "./tenancy";

/**
 * 全テストが共有する MSW の server。起動と後片付けは setup.ts が行い、handler はテストごとに server.use() で足す。
 * 業務の画面はテナントを選んだ後に出すため、選んだテナントの API だけは既定で応える。
 */
export const server = setupServer(getFindCurrentTenantMockHandler(tradingTenant));
```

`tradingTenant` は `apps/web/src/testing/tenancy.ts` の固定の値である（抜粋）。

```ts
// テナントの画面のテストが共有する固定の値（生成された MSW handler に渡す）。
export const tradingTenant: MembershipResponse = {
  tenantId: "44444444-4444-4444-8444-444444444444",
  tenantName: "サンプル商事",
};
```

典型的な例は、`apps/web/src/testing/orders.ts` の、値を変えられる fixture と、失敗の応答と、生成 handler を足す関数である（抜粋）。

```ts
export function draftOrder(overrides: Partial<OrderDetailsResponse> = {}): OrderDetailsResponse {
  return {
    orderId,
    customerOrderCode: "C-001",
    status: "DRAFT",
    orderedAt: "2026-10-05T01:02:03.123456Z",
    totalAmount: 240,
    lockNo: 1,
    lines: [{ lineNumber: 1, productId: penId, quantity: 2, unitPrice: 120, amount: 240 }],
    ...overrides,
  };
}

/** backend と同じ application/problem+json の失敗の応答。 */
export function problemResponse(
  status: number,
  title: string,
  extra: Record<string, unknown> = {},
) {
  return HttpResponse.json(
    { type: "about:blank", title, status, ...extra },
    { status, headers: { "Content-Type": "application/problem+json" } },
  );
}

/** 詳細の GET が返す注文を、テストの途中で差し替えられるようにする。決済の参照は既定で 0 件を返す。 */
export function serveDetail(initial: OrderDetailsResponse) {
  const state = { order: initial };
  server.use(
    getFindOrderByIdMockHandler(() => state.order),
    getListPaymentsMockHandler({ items: [] }),
  );
  return state;
}
```

テストは fixture を生成 handler に渡し、`renderRoute` で画面を描画する（`features/orders/pages/order-detail-payment.test.tsx`）。

```tsx
  it("決済記録の状態が知らない値なら、決済済みとせず不明を出す", async () => {
    serveDetail(draftOrder({ status: "CONFIRMED" }));
    server.use(getListPaymentsMockHandler({ items: [payment({ status: "REFUNDED" })] }));

    await renderRoute(detailPath);

    const region = await expectPaymentStatus(
      "決済の状態が分かりません。管理者に連絡してください。",
    );
    expect(within(region).getByText("請求した金額")).toBeTruthy();
  });
```

## 対応するテスト

fixture と handler に専用のテストファイルはない。
これらは、使う component test を通して動く。
`server.listen({ onUnhandledRequest: "error" })` により、handler を足し忘れた要求はエラーになる。
`src/testing/**` は coverage の計測から外している。
fixture に渡す値の形が契約と合わないと、生成 handler の引数の型で型検査が落ちる。
使われなくなった fixture と handler の export は、Knip が未使用の export として報告する。

## アンチパターン

- Orval の生成関数や `apiFetch` を `vi.mock` で差し替え、HTTP の境界を通さない。
- 生成 handler を引数なしで呼ぶ。
  配列の件数が実行ごとに変わり、テストの結果が揺れる。
- 生成型で型を付けずに、応答の値を object のリテラルで書く。
  契約が変わっても型検査で気付けない。
- 失敗の応答を `Content-Type: application/problem+json` なしで返す。
  `apiFetch` が `problem` を読まず、画面の分岐が本番と変わる。
- `src/generated/mocks` の生成物を手で直す、または手書きの handler をそこに置く。
- 1 つのテストだけが使う handler を `src/testing/` に置く。

## 作成時のチェックリスト

- [ ] server は package ごとに `src/testing/msw.ts` の一つにし、起動と後片付けを `setup.ts` に任せる。［自分で点検］
- [ ] 2 つ以上のテストファイルが使う fixture と handler を `testing/<feature>.ts` に置き、ファイル名を feature の名前にする。［自分で点検］
- [ ] ファイル名を kebab-case にする。［Oxlint で検査：unicorn/filename-case］
- [ ] 応答の値は `@frontend/api/models` の生成型で型を付ける。［自分で点検］
- [ ] 生成 handler に渡す値の形が契約と合う。［型検査で検査：生成 handler の引数の型］
- [ ] 生成 handler には、fixture か fixture を返す関数を必ず渡す。［自分で点検］
- [ ] 失敗の応答は `application/problem+json` と Problem Details の本文で返す。［自分で点検］
- [ ] ID は版と variant の桁が正しい UUID にし、時刻は `Z` 付きのマイクロ秒精度にする。［自分で点検］
- [ ] `src/generated/mocks` を手で書き換えない。［Task で検査：api-client-check（エージェントの書き込みは Kiro のフックの block-generated-writes が止める）］
- [ ] production source から `msw`、`@frontend/api/mocks/**`、`@/testing/**` を import しない。［Oxlint で検査：no-restricted-imports］
- [ ] module を `vi.mock` と `vi.doMock` で差し替えない。［Oxlint で検査：vitest/no-restricted-vi-methods］
- [ ] 使わなくなった fixture と handler の export を残さない。［Knip で検査：task fe-knip（未使用の export）］
