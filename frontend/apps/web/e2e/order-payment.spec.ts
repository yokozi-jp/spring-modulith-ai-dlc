import type { Page, TestInfo } from "@playwright/test";

import { listPayments, readJson } from "./api";
import type { Product } from "./fixtures";
import { expect, test, uniqueCode } from "./fixtures";
import type { OrderCreatePage } from "./order-create-page";
import type { OrderDetailPage } from "./order-detail-page";
import type { OrderListPage } from "./order-list-page";
import type { OrderStatusLabel } from "./order-status";
import { chargeRequestCount, failChargesFor } from "./payment-gateway";
import type { ChargeFailure } from "./payment-gateway";

// 注文の作成から確定、Listener の請求、決済の表示までを、実際の backend、DB、イベントの Listener、決済代行の HTTP の Client を通して確かめる。
// 決済代行の応答は、WireMock の管理 API で注文ごとに実行中に切り替える（ADR-072）。
// 出版の状態、試行の回数、再投入の後の決済記録は、読む公開の入口も再投入の公開の入口も無いため、
// backend の PaymentGatewayClientIntegrationTest が本番の Client と既存の再投入の入口で確かめる。

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await testInfo.attach(name, { body: await page.screenshot(), contentType: "image/png" });
}

interface OrderScreens {
  page: Page;
  orderCreatePage: OrderCreatePage;
  orderDetailPage: OrderDetailPage;
}

// 決済の欄が自分で読み直して文言を変えるのを、画面を読み直さずに待つ。
async function expectPaymentText(detail: OrderDetailPage, text: string): Promise<void> {
  await expect(detail.paymentStatus).toHaveText(text, { timeout: detail.paymentTextTimeoutMs });
}

async function createAndConfirm(
  { orderCreatePage, orderDetailPage }: OrderScreens,
  { product, prefix }: { product: Product; prefix: string },
  beforeConfirm?: (orderId: string) => Promise<void>,
): Promise<{ orderId: string; code: string; total: number }> {
  const quantity = 2;
  const code = uniqueCode(prefix);
  const orderId = await orderCreatePage.create({
    code,
    productName: product.productName,
    quantity,
  });
  await beforeConfirm?.(orderId);
  await orderDetailPage.confirmButton.click();
  await expect(orderDetailPage.confirmedNotice).toBeVisible();
  return { orderId, code, total: product.unitPrice * quantity };
}

test("注文を作成し確定すると、Listener が決済代行へ請求し、決済済みと金額を表示する", async ({
  page,
  orderCreatePage,
  orderDetailPage,
  orderListPage,
  productFactory,
}, testInfo) => {
  const { orderId, code, total } = await createAndConfirm(
    { page, orderCreatePage, orderDetailPage },
    { product: await productFactory(), prefix: "E2E-PAY" },
  );

  await expectPaymentText(orderDetailPage, "決済済み");
  const payments = await listPayments(page.request, orderId);
  expect(payments, `orderId=${orderId} の決済記録`).toHaveLength(1);
  expect(payments[0]?.amount, `orderId=${orderId} の決済した金額`).toBe(total);
  await expect(orderDetailPage.paymentRegion).toContainText(
    new Intl.NumberFormat("ja-JP", { style: "currency", currency: "JPY" }).format(total),
  );
  expect(await chargeRequestCount(page.request, orderId), `orderId=${orderId} の請求`).toBe(1);
  await attachScreenshot(page, testInfo, "paid");

  await orderListPage.openDetail(code);
  await expectPaymentText(orderDetailPage, "決済済み");
});

// 決済代行を成功に戻した後に読み直しのボタンで読み直しても、決済の欄は再投入待ちのままで、請求は 1 回のままである。
// 戻した直後の読み直しの期間だけを確かめる。
async function expectNotResubmitted(
  { page, orderDetailPage }: OrderScreens,
  orderId: string,
): Promise<void> {
  await orderDetailPage.rereadPaymentButton.click();
  await expectPaymentText(orderDetailPage, orderDetailPage.paymentNotYetText);
  expect(await chargeRequestCount(page.request, orderId), `orderId=${orderId} の請求`).toBe(1);
}

// 一時障害のあいだは決済記録が無い。E2E の backend は `.env.test` で定期の再投入を無効にしているため（ADR-079）、成功に戻しても自動では再投入しない。
async function expectTransientFailure(
  screens: OrderScreens,
  testInfo: TestInfo,
  { product, failure }: { product: Product; failure: ChargeFailure },
): Promise<void> {
  // 決済の欄の読み直しの期間（15 秒）を、確定の後と読み直しのボタンの後の二度待つため、既定の 30 秒を超える。
  test.slow();
  const restores: (() => Promise<void>)[] = [];
  const { orderId } = await createAndConfirm(
    screens,
    { product, prefix: "E2E-FAIL" },
    async (id) => {
      restores.push(await failChargesFor(screens.page.request, id, failure));
    },
  );
  try {
    // backend の本番の Client が、切り替えた WireMock を呼んだ。
    await expect
      .poll(() => chargeRequestCount(screens.page.request, orderId))
      .toBeGreaterThanOrEqual(1);
    // 欄は読み直しの期間が過ぎるまで処理中を出し、その後に再投入待ちを出す。
    await expectPaymentText(screens.orderDetailPage, screens.orderDetailPage.paymentNotYetText);
    expect(await listPayments(screens.page.request, orderId), `orderId=${orderId}`).toEqual([]);
    await attachScreenshot(screens.page, testInfo, `${failure.kind}-not-yet`);
  } finally {
    await Promise.all(restores.map((restore) => restore()));
  }
  await expectNotResubmitted(screens, orderId);
}

test("決済代行が 5xx を返すと、決済記録を作らず、成功に戻しても自動では再投入しない", async ({
  page,
  orderCreatePage,
  orderDetailPage,
  productFactory,
}, testInfo) => {
  await expectTransientFailure({ page, orderCreatePage, orderDetailPage }, testInfo, {
    product: await productFactory(),
    failure: { kind: "status", status: 503 },
  });
});

test("決済代行の応答がタイムアウトすると、決済記録を作らず、成功に戻しても自動では再投入しない", async ({
  page,
  orderCreatePage,
  orderDetailPage,
  productFactory,
}, testInfo) => {
  await expectTransientFailure({ page, orderCreatePage, orderDetailPage }, testInfo, {
    product: await productFactory(),
    failure: { kind: "delay", milliseconds: 3000 },
  });
});

test("決済代行が拒否すると、拒否を表示する", async ({
  page,
  orderCreatePage,
  orderDetailPage,
  orderListPage,
  productFactory,
}) => {
  const product = await productFactory();
  const restores: (() => Promise<void>)[] = [];
  try {
    const { code } = await createAndConfirm(
      { page, orderCreatePage, orderDetailPage },
      { product, prefix: "E2E-DECL" },
      async (id) => {
        restores.push(await failChargesFor(page.request, id, { kind: "declined" }));
      },
    );

    await expectPaymentText(orderDetailPage, "決済代行が決済を拒否しました。");
    await orderListPage.openDetail(code);
    await expectPaymentText(orderDetailPage, "決済代行が決済を拒否しました。");
  } finally {
    await Promise.all(restores.map((restore) => restore()));
  }
});

test("参照の要求は CSRF の token なしで成功し、更新の要求は token が無ければ 403 で拒まれる", async ({
  page,
  productFactory,
}) => {
  const product = await productFactory();
  const products = await page.request.get("/api/v1/products");
  expect(products.status(), "GET /api/v1/products").toBe(200);

  // Playwright の request は X-XSRF-TOKEN を付けない。
  const code = uniqueCode("E2E-CSRF");
  const rejected = await page.request.post("/api/v1/orders", {
    data: { customerOrderCode: code, lines: [{ productId: product.productId, quantity: 1 }] },
  });
  expect(rejected.status(), "token のない POST /api/v1/orders").toBe(403);

  const orders = await page.request.get("/api/v1/orders");
  expect(orders.status(), "GET /api/v1/orders").toBe(200);
  const body = await readJson<{ items: { customerOrderCode: string }[] }>(orders);
  expect(body.items.map((item) => item.customerOrderCode)).not.toContain(code);
});

test("画面の作成は、CSRF の Cookie の値を X-XSRF-TOKEN で送って成功する", async ({
  page,
  orderCreatePage,
  productFactory,
}) => {
  const product = await productFactory();
  const created = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/v1/orders") && response.request().method() === "POST",
  );

  await orderCreatePage.create({
    code: uniqueCode("E2E-CSRF"),
    productName: product.productName,
    quantity: 1,
  });

  const response = await created;
  expect(response.status(), "画面からの POST /api/v1/orders").toBe(201);
  expect(await response.request().headerValue("x-xsrf-token")).toBeTruthy();
});

async function createInStatus(
  { orderCreatePage, orderDetailPage }: Pick<OrderScreens, "orderCreatePage" | "orderDetailPage">,
  product: Product,
  label: OrderStatusLabel,
): Promise<string> {
  const code = uniqueCode("E2E-LIST");
  await orderCreatePage.create({ code, productName: product.productName, quantity: 1 });
  if (label === "確定済み") {
    await orderDetailPage.confirmButton.click();
    await expect(orderDetailPage.confirmedNotice).toBeVisible();
  } else if (label === "取消済み") {
    await orderDetailPage.cancelButton.click();
    await expect(orderDetailPage.cancelledNotice).toBeVisible();
  }
  return code;
}

async function expectBucket(
  orderListPage: OrderListPage,
  codes: Readonly<Record<OrderStatusLabel, string>>,
  label: OrderStatusLabel,
): Promise<void> {
  await orderListPage.statusLink(label).click();
  await expect(orderListPage.statusLink(label)).toHaveAttribute("aria-current", "page");
  await Promise.all(
    Object.values(codes).map((code) =>
      expect(orderListPage.codeCell(code)).toHaveCount(code === codes[label] ? 1 : 0),
    ),
  );
}

test.describe("一覧と詳細と作成の画面で、注文と商品の状態を表示する", () => {
  test("一覧の状態の絞り込みは、注文を状態ごとに分ける", async ({
    orderCreatePage,
    orderDetailPage,
    orderListPage,
    productFactory,
  }) => {
    const product = await productFactory();
    const screens = { orderCreatePage, orderDetailPage };
    const codes = {
      下書き: await createInStatus(screens, product, "下書き"),
      確定済み: await createInStatus(screens, product, "確定済み"),
      取消済み: await createInStatus(screens, product, "取消済み"),
    };

    await orderListPage.goto();
    await expectBucket(orderListPage, codes, "下書き");
    await expectBucket(orderListPage, codes, "確定済み");
    await expectBucket(orderListPage, codes, "取消済み");
  });

  test("取消済みの注文の詳細は、決済の対象でないことを表示する", async ({
    orderCreatePage,
    orderDetailPage,
    orderListPage,
    productFactory,
  }) => {
    const code = await createInStatus(
      { orderCreatePage, orderDetailPage },
      await productFactory(),
      "取消済み",
    );

    await orderListPage.openDetail(code);

    await expectPaymentText(orderDetailPage, "この注文は決済の対象ではありません。");
  });

  test("作成の画面の商品の選択肢に、販売終了の商品を出さない", async ({
    orderCreatePage,
    productFactory,
  }) => {
    const discontinued = await productFactory({ onSale: false });
    const onSale = await productFactory();

    await orderCreatePage.goto();

    const select = orderCreatePage.productSelect;
    await expect(select.getByRole("option", { name: discontinued.productName })).toHaveCount(0);
    await expect(select.getByRole("option", { name: onSale.productName })).toHaveCount(1);
  });
});
