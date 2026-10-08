import type { APIRequestContext, APIResponse } from "@playwright/test";

import { paymentGatewayUrl } from "./environment";
import { expect, test } from "./fixtures";
import { failChargesFor } from "./payment-gateway";

// この spec は、compose-test の WireMock のスタブと、payment-gateway.ts の管理 API の補助だけを検証する。
// backend を通した経路は検証しない。
// backend を通して請求の成否を切り替える検証は #167 で行う。

// WireMock だけを呼び、ログインのセッションを使わない。
test.use({ storageState: { cookies: [], origins: [] } });

// backend の Client と同じ形で、決済代行の WireMock へ直接請求する。
function charge(request: APIRequestContext, orderId: string): Promise<APIResponse> {
  return request.post(`${paymentGatewayUrl}/v1/charges`, {
    headers: { "Idempotency-Key": orderId },
    data: { orderId, amount: 240, currency: "JPY" },
  });
}

async function chargeStatus(request: APIRequestContext, orderId: string): Promise<number> {
  const response = await charge(request, orderId);
  return response.status();
}

test.describe("決済代行の WireMock のスタブと管理 API の補助（backend を通した請求の切り替えは #167 で検証する）", () => {
  test("決済代行の WireMock は、共有のスタブで注文 ID から決めた識別子を返す", async ({
    request,
  }) => {
    const orderId = crypto.randomUUID();

    const response = await charge(request, orderId);

    expect(response.status(), `orderId=${orderId} の請求`).toBe(201);
    expect(await response.json()).toEqual({ chargeId: `ch_${orderId}`, status: "SUCCEEDED" });
  });

  test("管理 API で足した 5xx は、その注文の請求だけを失敗させ、取り除くと成功に戻る", async ({
    request,
  }) => {
    const failing = crypto.randomUUID();
    const other = crypto.randomUUID();
    const restore = await failChargesFor(request, failing, { kind: "status", status: 503 });
    try {
      expect(await chargeStatus(request, failing), `orderId=${failing} の請求`).toBe(503);
      expect(await chargeStatus(request, other), `orderId=${other} の請求`).toBe(201);
    } finally {
      await restore();
    }

    expect(await chargeStatus(request, failing), `取り除いた後の orderId=${failing}`).toBe(201);
  });

  test("管理 API で足した拒否は、その注文の請求だけに 201 の DECLINED を返す", async ({
    request,
  }) => {
    const declined = crypto.randomUUID();
    const other = crypto.randomUUID();
    const restore = await failChargesFor(request, declined, { kind: "declined" });
    try {
      const response = await charge(request, declined);

      expect(response.status(), `orderId=${declined} の請求`).toBe(201);
      expect(await response.json()).toEqual({ chargeId: `ch_${declined}`, status: "DECLINED" });
      expect(await chargeStatus(request, other), `orderId=${other} の請求`).toBe(201);
    } finally {
      await restore();
    }
  });

  test("管理 API で足した遅延は、backend の呼び出しのタイムアウト 2 秒より長く応答を遅らせる", async ({
    request,
  }) => {
    const orderId = crypto.randomUUID();
    const restore = await failChargesFor(request, orderId, { kind: "delay", milliseconds: 3000 });
    try {
      const started = Date.now();
      const response = await charge(request, orderId);

      expect(response.status(), `orderId=${orderId} の請求`).toBe(201);
      expect(Date.now() - started, `orderId=${orderId} の応答までの時間`).toBeGreaterThanOrEqual(
        3000,
      );
    } finally {
      await restore();
    }
  });
});
