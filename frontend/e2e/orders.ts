import type { APIRequestContext, APIResponse, Locator, Page } from "@playwright/test";

import { expect } from "./fixtures";

// 注文の画面を操作する補助。商品は task e2e のシーダーが入れ、spec は読むだけにする（ADR-073）。

interface Product {
  productId: string;
  productName: string;
  salesStatus: string;
  unitPrice: number;
}

// 公開 API の応答の本文。形は backend の契約テストが保証するため、ここでは検証しない（docs/frontend/api-client-orval.md）。
export async function readJson<Body>(response: APIResponse): Promise<Body> {
  // oxlint-disable-next-line typescript/no-unsafe-type-assertion
  return (await response.json()) as Body;
}

// シーダーの商品名は乱数の値なので、名前を固定で書かず GET /api/products（CSRF の token は要らない）から選ぶ。
export async function listProducts(request: APIRequestContext): Promise<Product[]> {
  const response = await request.get("/api/products");
  expect(response.status(), "GET /api/products").toBe(200);
  const body = await readJson<{ items: Product[] }>(response);
  return body.items;
}

export async function onSaleProduct(request: APIRequestContext): Promise<Product> {
  const products = await listProducts(request);
  const product = products.find((item) => item.salesStatus === "ON_SALE");
  if (product === undefined) {
    throw new Error("販売中の商品がありません。task e2e がシーダーを実行したか確かめてください。");
  }
  return product;
}

// 並列のテストと衝突しない客先注文番号。上限の 30 文字に収める。
export function uniqueCode(prefix: string): string {
  return `${prefix}-${crypto.randomUUID().slice(0, 18)}`;
}

// 注文の作成の画面で、商品 1 つの明細の下書きを作り、詳細の画面の注文 ID を返す。
export async function createOrder(
  page: Page,
  order: { code: string; productName: string; quantity: number },
): Promise<string> {
  await page.goto("/orders/new");
  await page.getByLabel("客先注文番号").fill(order.code);
  // 作成の画面は空の明細を 1 行持って開くので、明細を追加せずにその行を埋める。
  await page.getByLabel("商品").selectOption({ label: order.productName });
  await page.getByLabel("数量").fill(String(order.quantity));
  await page.getByRole("button", { name: "作成する" }).click();
  await page.waitForURL(/\/orders\/[0-9a-f-]{36}$/u);
  return new URL(page.url()).pathname.slice("/orders/".length);
}

// 詳細の画面の「決済」の欄の状態の文言（live region）。
function paymentOutput(page: Page): Locator {
  return page.getByRole("region", { name: "決済" }).getByRole("status");
}

// 決済の欄の文言を待つ上限。欄は確定から 15 秒まで自分で読み直すため、その期間と Listener の処理の時間より長くする。
const PAYMENT_TEXT_TIMEOUT_MS = 30_000;

// 決済の欄が自分で読み直して文言を変えるのを、画面を読み直さずに待つ。
export async function expectPaymentText(page: Page, text: string): Promise<void> {
  await expect(paymentOutput(page)).toHaveText(text, { timeout: PAYMENT_TEXT_TIMEOUT_MS });
}

// 注文の決済記録を、公開 API（GET、CSRF の token は要らない）で読む。
export async function listPayments(
  request: APIRequestContext,
  orderId: string,
): Promise<{ status: string; amount: number }[]> {
  const response = await request.get(`/api/payments?orderId=${orderId}`);
  expect(response.status(), `GET /api/payments?orderId=${orderId}`).toBe(200);
  const body = await readJson<{ items: { status: string; amount: number }[] }>(response);
  return body.items;
}
