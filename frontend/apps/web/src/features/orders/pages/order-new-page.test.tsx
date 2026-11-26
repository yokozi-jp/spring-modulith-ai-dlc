/* @vitest-environment jsdom */

import {
  getFindOrderByIdMockHandler,
  getListOrdersMockHandler,
} from "@frontend/api/mocks/ordering/ordering.msw";
import { getListPaymentsMockHandler } from "@frontend/api/mocks/payment/payment.msw";
import { getListProductsMockHandler } from "@frontend/api/mocks/product/product.msw";
import { draftOrderHeaderIdempotencyKeyRegExp } from "@frontend/api/zod/ordering/ordering.zod";
import { screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vite-plus/test";

import { createGate } from "@/testing/gate";
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
  idempotencyKey: string | null;
  body: unknown;
}

/** 客先注文番号の一意制約に違反したときに backend が返す title。 */
const uniqueViolationTitle = "同じ値がすでに使われています";

/** 客先注文番号の一意制約に違反した 409。 */
function uniqueViolation() {
  return problemResponse(409, uniqueViolationTitle, { type: "/problems/unique-violation" });
}

/** 作成の要求を記録し、respond の応答を返す。 */
function serveDraftOrder(respond: () => Response) {
  const sent: SentRequest[] = [];
  server.use(
    http.post("*/api/v1/orders", async ({ request }) => {
      sent.push({
        csrf: request.headers.get("X-XSRF-TOKEN"),
        idempotencyKey: request.headers.get("Idempotency-Key"),
        body: await request.json(),
      });
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

// oxlint-disable-next-line max-statements -- 注文の作成の画面のテストを 1 つの describe に置き、準備を共有する。
describe("注文の作成の画面", () => {
  beforeEach(() => {
    stubCsrfCookie();
    server.use(getListProductsMockHandler(products));
  });

  it("作成に成功すると、Location の注文の詳細へ遷移し、要求に CSRF の header を付ける", async () => {
    const sent = serveDraftOrder(
      () =>
        new HttpResponse(undefined, {
          status: 201,
          headers: { Location: `${globalThis.location.origin}/api/v1/orders/${orderId}` },
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
        idempotencyKey: expect.stringMatching(draftOrderHeaderIdempotencyKeyRegExp) as unknown,
        body: { customerOrderCode: "C-001", lines: [{ productId: penId, quantity: 2 }] },
      },
    ]);
  });

  it("押すたびに、引用符で囲んだ UUID の新しい Idempotency-Key を送る", async () => {
    const sent = serveDraftOrder(() => problemResponse(409, "競合が発生しました"));
    const user = await submitFilledForm();
    await expect(within(notice()).findByText("競合が発生しました")).resolves.toBeTruthy();

    await user.click(screen.getByRole("button", { name: "作成する" }));

    await waitFor(() => {
      expect(sent).toHaveLength(2);
    });
    const [first, second] = sent.map((request) => request.idempotencyKey);
    expect(first).toMatch(draftOrderHeaderIdempotencyKeyRegExp);
    expect(second).toMatch(draftOrderHeaderIdempotencyKeyRegExp);
    expect(second).not.toBe(first);
  });

  it.each([
    ["別の origin", { Location: `https://other.example/api/v1/orders/${orderId}` }],
    ["ない", {}],
  ] as const)("Location が%sなら一覧へ遷移する", async (_case, headers) => {
    serveDraftOrder(() => new HttpResponse(undefined, { status: 201, headers }));
    server.use(getListOrdersMockHandler({ items: [] }));

    await submitFilledForm();

    await expect(screen.findByRole("heading", { level: 1, name: "注文" })).resolves.toBeTruthy();
  });

  it("送信中は 2 回目を送らず、送信中であることを通知する", async () => {
    const create = createGate();
    const sent = serveDraftOrder(() => uniqueViolation());
    server.use(http.post("*/api/v1/orders", () => create.promise, { once: true }));
    const user = await submitFilledForm();
    await expect(within(notice()).findByText("送信しています")).resolves.toBeTruthy();

    await user.click(screen.getByRole("button", { name: "作成する" }));
    create.release();

    await expect(within(notice()).findByText(uniqueViolationTitle)).resolves.toBeTruthy();
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
    {
      status: 409,
      type: "/problems/unique-violation",
      title: uniqueViolationTitle,
      description: "この客先注文番号は既に使われている可能性があります。",
    },
    {
      status: 422,
      type: "/problems/ordering/product-discontinued",
      title: "販売を終了した商品です",
      description: "入力した商品を確かめてください。",
    },
  ] as const)(
    "$status の $type では入力を残し、title と操作の説明を通知に出す",
    async ({ status, type, title, description }) => {
      serveDraftOrder(() => problemResponse(status, title, { type }));

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
    const sent = serveDraftOrder(() => uniqueViolation());
    const { user } = await renderRoute("/orders/new");

    await user.type(await screen.findByLabelText("客先注文番号"), "C-009");
    await user.tab();
    expect(screen.getByLabelText("商品")).toHaveFocus();
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
