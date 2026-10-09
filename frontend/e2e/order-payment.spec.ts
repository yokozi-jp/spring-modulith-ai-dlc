import type { Page, TestInfo } from "@playwright/test";

import { expect, test } from "./fixtures";
import {
  createOrder,
  expectPaymentText,
  listPayments,
  listProducts,
  onSaleProduct,
  readJson,
  uniqueCode,
} from "./orders";
import { chargeRequestCount, failChargesFor } from "./payment-gateway";
import type { ChargeFailure } from "./payment-gateway";

// 注文の作成から確定、Listener の請求、決済の表示までを、実際の backend、DB、イベントの Listener、決済代行の HTTP の Client を通して確かめる。
// 決済代行の応答は、WireMock の管理 API で注文ごとに実行中に切り替える（ADR-072）。
// 出版の状態、試行の回数、再投入の後の決済記録は、読む公開の入口も再投入の公開の入口も無いため、
// backend の PaymentGatewayClientIntegrationTest が本番の Client と既存の再投入の入口で確かめる。

const notYet = "決済をまだ受け付けていません。処理中か、失敗して再投入を待っています。";

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await testInfo.attach(name, { body: await page.screenshot(), contentType: "image/png" });
}

// 販売中の商品で下書きを作り、確定して、注文 ID と合計金額を返す。確定の前に決済代行の応答を切り替えられる。
async function createAndConfirm(
  page: Page,
  prefix: string,
  beforeConfirm?: (orderId: string) => Promise<void>,
): Promise<{ orderId: string; total: number }> {
  const product = await onSaleProduct(page.request);
  const quantity = 2;
  const orderId = await createOrder(page, {
    code: uniqueCode(prefix),
    productName: product.productName,
    quantity,
  });
  await beforeConfirm?.(orderId);
  await page.getByRole("button", { name: "確定する" }).click();
  await expect(page.getByText("注文を確定しました。")).toBeVisible();
  return { orderId, total: product.unitPrice * quantity };
}

test("注文を作成し確定すると、Listener が決済代行へ請求し、決済済みと金額を表示する", async ({
  page,
}, testInfo) => {
  const { orderId, total } = await createAndConfirm(page, "E2E-PAY");

  await expectPaymentText(page, "決済済み");
  const payments = await listPayments(page.request, orderId);
  expect(payments, `orderId=${orderId} の決済記録`).toHaveLength(1);
  expect(payments[0]?.amount, `orderId=${orderId} の決済した金額`).toBe(total);
  await expect(page.getByRole("region", { name: "決済" })).toContainText(
    new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY" }).format(total),
  );
  expect(await chargeRequestCount(page.request, orderId), `orderId=${orderId} の請求`).toBe(1);
  await attachScreenshot(page, testInfo, "paid");
});

// 決済代行を成功に戻した後に読み直しのボタンで読み直しても、決済の欄は再投入待ちのままで、請求は 1 回のままである。
// 戻した直後の読み直しの期間だけを確かめる。#108 で定期の再投入が入ったら、待つか状態を明示して観測する形に直す。
async function expectNotResubmitted(page: Page, orderId: string): Promise<void> {
  await page.getByRole("button", { name: "決済の状態を読み直す" }).click();
  await expectPaymentText(page, notYet);
  expect(await chargeRequestCount(page.request, orderId), `orderId=${orderId} の請求`).toBe(1);
}

// 一時障害のあいだは決済記録が無く、成功に戻しても自動では再投入しない（運用者の入口と定期の再投入は #108 で扱う）。
async function expectTransientFailure(
  page: Page,
  testInfo: TestInfo,
  failure: ChargeFailure,
): Promise<void> {
  // 決済の欄の読み直しの期間（15 秒）を、確定の後と読み直しのボタンの後の二度待つため、既定の 30 秒を超える。
  test.slow();
  const restores: (() => Promise<void>)[] = [];
  const { orderId } = await createAndConfirm(page, "E2E-FAIL", async (id) => {
    restores.push(await failChargesFor(page.request, id, failure));
  });
  try {
    // backend の本番の Client が、切り替えた WireMock を呼んだ。
    await expect.poll(() => chargeRequestCount(page.request, orderId)).toBeGreaterThanOrEqual(1);
    // 欄は読み直しの期間が過ぎるまで処理中を出し、その後に再投入待ちを出す。
    await expectPaymentText(page, notYet);
    expect(await listPayments(page.request, orderId), `orderId=${orderId}`).toEqual([]);
    await attachScreenshot(page, testInfo, `${failure.kind}-not-yet`);
  } finally {
    await Promise.all(restores.map((restore) => restore()));
  }
  await expectNotResubmitted(page, orderId);
}

test("決済代行が 5xx を返すと、決済記録を作らず、成功に戻しても自動では再投入しない", async ({
  page,
}, testInfo) => {
  await expectTransientFailure(page, testInfo, { kind: "status", status: 503 });
});

test("決済代行の応答がタイムアウトすると、決済記録を作らず、成功に戻しても自動では再投入しない", async ({
  page,
}, testInfo) => {
  await expectTransientFailure(page, testInfo, { kind: "delay", milliseconds: 3000 });
});

test("決済代行が拒否すると、拒否を表示する", async ({ page }) => {
  const restores: (() => Promise<void>)[] = [];
  try {
    await createAndConfirm(page, "E2E-DECL", async (id) => {
      restores.push(await failChargesFor(page.request, id, { kind: "declined" }));
    });

    await expectPaymentText(page, "決済代行が決済を拒否しました。");
  } finally {
    await Promise.all(restores.map((restore) => restore()));
  }
});

test("参照の要求は CSRF の token なしで成功し、更新の要求は token が無ければ 403 で拒まれる", async ({
  page,
}) => {
  const product = await onSaleProduct(page.request);
  const products = await page.request.get("/api/products");
  expect(products.status(), "GET /api/products").toBe(200);

  // Playwright の request は X-XSRF-TOKEN を付けない。
  const code = uniqueCode("E2E-CSRF");
  const rejected = await page.request.post("/api/orders", {
    data: { customerOrderCode: code, lines: [{ productId: product.productId, quantity: 1 }] },
  });
  expect(rejected.status(), "token のない POST /api/orders").toBe(403);

  const orders = await page.request.get("/api/orders");
  expect(orders.status(), "GET /api/orders").toBe(200);
  const body = await readJson<{ items: { customerOrderCode: string }[] }>(orders);
  expect(body.items.map((item) => item.customerOrderCode)).not.toContain(code);
});

test("画面の作成は、CSRF の Cookie の値を X-XSRF-TOKEN で送って成功する", async ({ page }) => {
  const product = await onSaleProduct(page.request);
  const created = page.waitForResponse(
    (response) => response.url().endsWith("/api/orders") && response.request().method() === "POST",
  );

  await createOrder(page, {
    code: uniqueCode("E2E-CSRF"),
    productName: product.productName,
    quantity: 1,
  });

  const response = await created;
  expect(response.status(), "画面からの POST /api/orders").toBe(201);
  expect(await response.request().headerValue("x-xsrf-token")).toBeTruthy();
});

async function expectDetail(page: Page, code: string, text: string): Promise<void> {
  await page.goto("/orders");
  await page.getByRole("link", { name: `${code} の詳細` }).click();
  // SEED-C06 は記録が無いため、読み直しの期間が過ぎてから再投入待ちを出す。
  await expectPaymentText(page, text);
}

test.describe("シーダーの代表の状態を、一覧と詳細と作成の画面で表示する（読むだけ）", () => {
  const allCodes = ["SEED-C01", "SEED-C02", "SEED-C03", "SEED-C04", "SEED-C05", "SEED-C06"];

  async function expectBucket(page: Page, label: string, codes: string[]): Promise<void> {
    await page
      .getByRole("navigation", { name: "状態で絞り込む" })
      .getByRole("link", { name: label })
      .click();
    await expect(page.getByRole("link", { name: label })).toHaveAttribute("aria-current", "page");
    await Promise.all(
      allCodes.map((code) =>
        expect(page.getByRole("cell", { name: code, exact: true })).toHaveCount(
          codes.includes(code) ? 1 : 0,
        ),
      ),
    );
  }

  test("一覧の状態の絞り込みは、代表の注文を状態ごとに分ける", async ({ page }) => {
    await page.goto("/orders");

    await expectBucket(page, "下書き", ["SEED-C01", "SEED-C02"]);
    await expectBucket(page, "確定済み", ["SEED-C03", "SEED-C05", "SEED-C06"]);
    await expectBucket(page, "取消済み", ["SEED-C04"]);
  });

  test("SEED-C03 の詳細は決済済みを表示する", async ({ page }) => {
    await expectDetail(page, "SEED-C03", "決済済み");
  });

  test("SEED-C05 の詳細は決済代行の拒否を表示する", async ({ page }) => {
    await expectDetail(page, "SEED-C05", "決済代行が決済を拒否しました。");
  });

  test("SEED-C06 の詳細は、決済記録の無い再投入待ちを表示する", async ({ page }) => {
    await expectDetail(page, "SEED-C06", notYet);
  });

  test("SEED-C04 の詳細は、決済の対象でないことを表示する", async ({ page }) => {
    await expectDetail(page, "SEED-C04", "この注文は決済の対象ではありません。");
  });

  test("作成の画面の商品の選択肢に、販売終了の商品を出さない", async ({ page }) => {
    const products = await listProducts(page.request);
    const discontinued = products.filter((item) => item.salesStatus === "DISCONTINUED");
    expect(discontinued, "シーダーの販売終了の商品").toHaveLength(1);
    const onSale = await onSaleProduct(page.request);

    await page.goto("/orders/new");

    const select = page.getByLabel("商品");
    await expect(
      select.getByRole("option", { name: discontinued[0]?.productName ?? "" }),
    ).toHaveCount(0);
    await expect(select.getByRole("option", { name: onSale.productName })).toHaveCount(1);
  });
});
