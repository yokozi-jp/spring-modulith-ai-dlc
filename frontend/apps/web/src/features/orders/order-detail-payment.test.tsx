/* @vitest-environment jsdom */

import { screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vite-plus/test";

import { getListPaymentsMockHandler } from "@/api/generated/mocks/payment/payment.msw";
import { getListProductsMockHandler } from "@/api/generated/mocks/product/product.msw";
import type { PaymentSummaryResponse } from "@/api/generated/models";
import { server } from "@/testing/msw";
import { draftOrder, orderId, products, serveDetail, stubCsrfCookie } from "@/testing/orders";
import { renderRoute } from "@/testing/render-route";

const detailPath = `/orders/${orderId}`;
const recordedAt = "2026-10-06T01:02:03.123456Z";

/** 注文の決済記録。 */
function payment(overrides: Partial<PaymentSummaryResponse> = {}): PaymentSummaryResponse {
  return {
    paymentId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    orderId,
    amount: 240,
    status: "PAID",
    recordedAt,
    ...overrides,
  };
}

/** 決済の欄（見出し「決済」の region）。 */
function paymentRegion() {
  return screen.findByRole("region", { name: "決済" });
}

/** 決済の欄の状態の文言が text になるまで待つ。 */
async function expectPaymentStatus(text: string) {
  const region = await paymentRegion();
  await waitFor(() => {
    expect(within(region).getByRole("status").textContent).toBe(text);
  });
  return region;
}

/** 決済の参照の応答を items() にし、受けた要求の数を数える。 */
function servePayments(items: () => PaymentSummaryResponse[]) {
  const counter = { requests: 0 };
  server.use(
    getListPaymentsMockHandler(() => {
      counter.requests += 1;
      return { items: items() };
    }),
  );
  return counter;
}

describe("order detail page payment status", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it.each([
    ["PAID", "決済済み"],
    ["DECLINED", "決済代行が決済を拒否しました。"],
    ["FAILED", "決済に失敗しました。再投入では回復しないため、管理者に連絡してください。"],
  ])(
    "決済記録が %s なら、その結果と金額と記録した時刻を出し、time に recordedAt を入れる",
    async (status, message) => {
      serveDetail(draftOrder({ status: "CONFIRMED" }));
      server.use(getListPaymentsMockHandler({ items: [payment({ status })] }));

      await renderRoute(detailPath);

      const region = await expectPaymentStatus(message);
      expect(within(region).getByText("請求した金額").nextElementSibling?.textContent).toBe(
        "￥240",
      );
      const time = within(region).getByText(
        new Intl.DateTimeFormat("ja", { dateStyle: "medium", timeStyle: "medium" }).format(
          Temporal.Instant.from(recordedAt).epochMilliseconds,
        ),
      );
      expect(time.tagName).toBe("TIME");
      expect(time.getAttribute("datetime")).toBe(recordedAt);
    },
  );

  it("決済記録の状態が知らない値なら、決済済みとせず不明を出す", async () => {
    serveDetail(draftOrder({ status: "CONFIRMED" }));
    server.use(getListPaymentsMockHandler({ items: [payment({ status: "REFUNDED" })] }));

    await renderRoute(detailPath);

    const region = await expectPaymentStatus(
      "決済の状態が分かりません。管理者に連絡してください。",
    );
    expect(within(region).getByText("請求した金額")).toBeTruthy();
  });

  it("確定した注文で決済記録がまだなければ処理中を出し、記録ができたら操作なしで結果を出す", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serveDetail(draftOrder({ status: "CONFIRMED" }));
    const counter = servePayments(() => (counter.requests < 3 ? [] : [payment()]));

    await renderRoute(detailPath);

    const region = await expectPaymentStatus("決済の結果を確かめています。");
    expect(within(region).queryByRole("button", { name: "決済の状態を読み直す" })).toBeNull();
    await vi.advanceTimersByTimeAsync(2000);
    await expectPaymentStatus("決済済み");
  });

  it("読み直しの期間が過ぎても記録がなければ、読み直しをやめてまだ受け付けていないことを出す", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serveDetail(draftOrder({ status: "CONFIRMED" }));
    const counter = servePayments(() => []);
    await renderRoute(detailPath);
    await expectPaymentStatus("決済の結果を確かめています。");

    await vi.advanceTimersByTimeAsync(16_000);
    await expectPaymentStatus(
      "決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。",
    );
    const stopped = counter.requests;
    await vi.advanceTimersByTimeAsync(5000);

    expect(counter.requests).toBe(stopped);
  });

  it("読み直しの期間が過ぎた後に読み直しのボタンを押すと、記録の結果を出す", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serveDetail(draftOrder({ status: "CONFIRMED" }));
    let items: PaymentSummaryResponse[] = [];
    servePayments(() => items);
    const { user } = await renderRoute(detailPath);
    await vi.advanceTimersByTimeAsync(16_000);
    const region = await expectPaymentStatus(
      "決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。",
    );

    items = [payment({ status: "DECLINED" })];
    await user.click(within(region).getByRole("button", { name: "決済の状態を読み直す" }));

    await expect(expectPaymentStatus("決済代行が決済を拒否しました。")).resolves.toBe(region);
  });

  it("下書きの注文では決済の参照を読み直さない", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serveDetail(draftOrder());
    const counter = servePayments(() => []);

    await renderRoute(detailPath);
    await expectPaymentStatus("この注文は決済の対象ではありません。");
    await vi.advanceTimersByTimeAsync(5000);

    expect(counter.requests).toBe(1);
  });

  it.each(["DRAFT", "CANCELLED"])(
    "%s で決済記録がなければ、決済の対象ではないことを出す",
    async (status) => {
      serveDetail(draftOrder({ status }));

      await renderRoute(detailPath);

      const region = await expectPaymentStatus("この注文は決済の対象ではありません。");
      expect(within(region).queryByText("結果を記録した時刻")).toBeNull();
    },
  );
});
