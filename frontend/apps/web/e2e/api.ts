import type { APIRequestContext, APIResponse } from "@playwright/test";

import { expect } from "./fixtures.ts";

// 公開 API の応答の本文。形は backend の契約テストが保証するため、ここでは検証しない（docs/frontend/api-client-orval.md）。
export async function readJson<Body>(response: APIResponse): Promise<Body> {
  // oxlint-disable-next-line typescript/no-unsafe-type-assertion -- Playwright の response.json() は any を返すため、型引数 Body へ断定する。
  return (await response.json()) as Body;
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
