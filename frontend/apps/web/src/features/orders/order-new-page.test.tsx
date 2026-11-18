/* @vitest-environment jsdom */

import { screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

import {
  getFindOrderByIdMockHandler,
  getListOrdersMockHandler,
} from "@/api/generated/mocks/ordering/ordering.msw";
import { getListPaymentsMockHandler } from "@/api/generated/mocks/payment/payment.msw";
import { getListProductsMockHandler } from "@/api/generated/mocks/product/product.msw";
import { server } from "@/testing/msw";
import {
  csrfCookieValue,
  draftOrder,
  errorMessageOf,
  notebookId,
  orderId,
  penId,
  problemResponse,
  products,
  stubCsrfCookie,
} from "@/testing/orders";
import { renderRoute } from "@/testing/render-route";

interface SentRequest {
  csrf: string | null;
  body: unknown;
}

/** 作成の要求を記録し、respond の応答を返す。 */
function serveDraftOrder(respond: () => Response) {
  const sent: SentRequest[] = [];
  server.use(
    http.post("*/api/orders", async ({ request }) => {
      sent.push({ csrf: request.headers.get("X-XSRF-TOKEN"), body: await request.json() });
      return respond();
    }),
  );
  return sent;
}

async function fillForm(user: UserEvent) {
  await user.type(await screen.findByLabelText("客先注文番号"), "C-001");
  await user.selectOptions(screen.getByLabelText("商品"), "ボールペン");
  const quantity = screen.getByLabelText("数量");
  await user.clear(quantity);
  await user.type(quantity, "2");
}

async function submitFilledForm() {
  const { user } = await renderRoute("/orders/new");
  await fillForm(user);
  await user.click(screen.getByRole("button", { name: "作成する" }));
  return user;
}

function notice() {
  return screen.getByRole("status");
}

describe("order new page", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it("作成に成功すると、Location の注文の詳細へ遷移し、要求に CSRF の header を付ける", async () => {
    const sent = serveDraftOrder(
      () =>
        new HttpResponse(undefined, {
          status: 201,
          headers: { Location: `${globalThis.location.origin}/api/orders/${orderId}` },
        }),
    );
    server.use(
      getFindOrderByIdMockHandler(draftOrder()),
      getListPaymentsMockHandler({ items: [] }),
    );
    const { user, router } = await renderRoute("/orders/new");

    await fillForm(user);
    await user.click(screen.getByRole("button", { name: "作成する" }));

    await expect(
      screen.findByRole("heading", { level: 1, name: "注文 C-001" }),
    ).resolves.toBeTruthy();
    expect(router.state.location.pathname).toBe(`/orders/${orderId}`);
    expect(sent).toStrictEqual([
      {
        csrf: csrfCookieValue,
        body: { customerOrderCode: "C-001", lines: [{ productId: penId, quantity: 2 }] },
      },
    ]);
  });

  it.each([
    ["別の origin", { Location: `https://other.example/api/orders/${orderId}` }],
    ["ない", {}],
  ] as const)("Location が%sなら一覧へ遷移する", async (_case, headers) => {
    serveDraftOrder(() => new HttpResponse(undefined, { status: 201, headers }));
    server.use(getListOrdersMockHandler({ items: [] }));

    await submitFilledForm();

    await expect(screen.findByRole("heading", { level: 1, name: "注文" })).resolves.toBeTruthy();
  });

  it("送信中は 2 回目を送らず、送信中であることを通知する", async () => {
    let release = false;
    const sent = serveDraftOrder(() => problemResponse(409, "競合が発生しました"));
    server.use(
      http.post(
        "*/api/orders",
        async () => {
          await vi.waitUntil(() => release);
        },
        { once: true },
      ),
    );
    const user = await submitFilledForm();
    await expect(within(notice()).findByText("送信しています")).resolves.toBeTruthy();

    await user.click(screen.getByRole("button", { name: "作成する" }));
    release = true;

    await expect(within(notice()).findByText("競合が発生しました")).resolves.toBeTruthy();
    expect(sent).toHaveLength(1);
  });

  it("そのほかの失敗は一般の文言を通知し、detail を出さない", async () => {
    serveDraftOrder(() => problemResponse(500, "Internal Server Error", { detail: "stack trace" }));

    await submitFilledForm();

    await expect(within(notice()).findByText("操作を完了できませんでした")).resolves.toBeTruthy();
    expect(notice().textContent).not.toContain("stack trace");
  });

  it("送信時の検証の誤りを入力欄の直下に出し、送信しない", async () => {
    const sent = serveDraftOrder(() => new HttpResponse(undefined, { status: 201 }));
    const { user } = await renderRoute("/orders/new");
    await user.clear(await screen.findByLabelText("数量"));

    await user.click(screen.getByRole("button", { name: "作成する" }));

    await waitFor(() => {
      expect(screen.getByLabelText("客先注文番号").getAttribute("aria-invalid")).toBe("true");
    });
    expect(
      ["客先注文番号", "商品", "数量"].map((label) => errorMessageOf(screen.getByLabelText(label))),
    ).toStrictEqual([
      "客先注文番号を入力してください。",
      "商品を選んでください。",
      "数量を入力してください。",
    ]);
    expect(sent).toHaveLength(0);
  });

  describe("400 の errors", () => {
    beforeEach(() => {
      serveDraftOrder(() =>
        problemResponse(400, "入力が正しくありません", {
          type: "/problems/validation-error",
          errors: [
            { pointer: "/customerOrderCode", detail: "客先注文番号の形式が正しくありません" },
            { pointer: "/lines/0/quantity", detail: "数量が大きすぎます" },
            { pointer: "/unknown", detail: "ほかの誤り" },
          ],
        }),
      );
    });

    it("入力欄の近くに出し、写せないものは通知に並べる", async () => {
      await submitFilledForm();

      await waitFor(() => {
        expect(errorMessageOf(screen.getByLabelText("客先注文番号"))).toBe(
          "客先注文番号の形式が正しくありません",
        );
      });
      expect(errorMessageOf(screen.getByLabelText("数量"))).toBe("数量が大きすぎます");
      expect(within(notice()).getByText("入力が正しくありません")).toBeTruthy();
      expect(within(notice()).getByText("ほかの誤り")).toBeTruthy();
    });

    it("入力を直すと、その欄のサーバーの誤りを消す", async () => {
      const user = await submitFilledForm();
      const code = screen.getByLabelText("客先注文番号");
      await waitFor(() => {
        expect(code.getAttribute("aria-invalid")).toBe("true");
      });

      await user.type(code, "X");

      expect(code.getAttribute("aria-invalid")).toBeNull();
      expect(errorMessageOf(screen.getByLabelText("数量"))).toBe("数量が大きすぎます");
    });
  });

  it.each([
    [409, "競合が発生しました", "この客先注文番号は既に使われている可能性があります。"],
    [422, "処理できない内容です", "入力した商品を確かめてください。"],
  ] as const)(
    "%i では入力を残し、title と操作の説明を通知に出す",
    async (status, title, description) => {
      serveDraftOrder(() => problemResponse(status, title));

      await submitFilledForm();

      await expect(within(notice()).findByText(title)).resolves.toBeTruthy();
      expect(notice().textContent).toContain(description);
      expect(screen.getByLabelText("客先注文番号")).toHaveProperty("value", "C-001");
      expect(screen.getByLabelText("客先注文番号").getAttribute("aria-invalid")).toBeNull();
      expect(screen.getByLabelText("数量")).toHaveProperty("value", "2");
    },
  );

  it("販売終了の商品を選択肢に出さない", async () => {
    await renderRoute("/orders/new");

    const options = within(await screen.findByLabelText("商品")).getAllByRole("option");
    expect(options.map((option) => option.textContent)).toStrictEqual([
      "商品を選んでください",
      "ボールペン",
      "ノート",
    ]);
  });

  it("keyboard だけで入力して送信できる", async () => {
    const sent = serveDraftOrder(() => problemResponse(409, "競合が発生しました"));
    const { user } = await renderRoute("/orders/new");

    await user.type(await screen.findByLabelText("客先注文番号"), "C-009");
    await user.tab();
    expect(document.activeElement).toBe(screen.getByLabelText("商品"));
    await user.selectOptions(screen.getByLabelText("商品"), "ノート");
    await user.type(screen.getByLabelText("数量"), "{Enter}");

    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.body).toStrictEqual({
      customerOrderCode: "C-009",
      lines: [{ productId: notebookId, quantity: 1 }],
    });
  });
});
