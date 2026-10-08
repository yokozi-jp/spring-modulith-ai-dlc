import type { APIRequestContext } from "@playwright/test";

import { paymentGatewayUrl } from "./environment";
import { expect } from "./fixtures";

// 決済代行の WireMock に足す失敗と拒否（ADR-072）。
// status は応答の status、delay は成功の応答を遅らせる時間、declined は 201 の DECLINED の拒否。
export type ChargeFailure =
  | { kind: "status"; status: number }
  | { kind: "delay"; milliseconds: number }
  | { kind: "declined" };

function charged(orderId: string, status: "SUCCEEDED" | "DECLINED") {
  return {
    status: 201,
    headers: { "Content-Type": "application/json" },
    jsonBody: { chargeId: `ch_${orderId}`, status },
  };
}

function chargeResponse(orderId: string, failure: ChargeFailure) {
  if (failure.kind === "status") {
    return { status: failure.status };
  }
  if (failure.kind === "delay") {
    return { ...charged(orderId, "SUCCEEDED"), fixedDelayMilliseconds: failure.milliseconds };
  }
  return charged(orderId, "DECLINED");
}

// 実行中に足したスタブを消し、docker/wiremock/mappings の共有のスタブだけに戻す。
// 前の実行で後片付けが走らずに残った注文ごとのスタブを持ち越さないよう、E2E の開始時に一度呼ぶ。
export async function resetPaymentGatewayStubs(request: APIRequestContext): Promise<void> {
  const reset = await request.post(`${paymentGatewayUrl}/__admin/mappings/reset`);
  expect(reset.ok(), "決済代行の WireMock のスタブの初期化").toBe(true);
}

// 注文 ID の請求だけを失敗させる、または拒否させるスタブを、WireMock の管理 API で実行中に足し、取り除く関数を返す。
// 冪等性キー（注文 ID）で絞り、共有のスタブより優先度を高くするため、並列のテストと他の注文は成功のままになる。
export async function failChargesFor(
  request: APIRequestContext,
  orderId: string,
  failure: ChargeFailure,
): Promise<() => Promise<void>> {
  const response = chargeResponse(orderId, failure);
  // 取り除くときに指す ID を自分で決めて渡し、応答の本文を読まずに済ませる。
  const id = crypto.randomUUID();
  const created = await request.post(`${paymentGatewayUrl}/__admin/mappings`, {
    data: {
      id,
      priority: 1,
      request: {
        method: "POST",
        url: "/v1/charges",
        headers: { "Idempotency-Key": { equalTo: orderId } },
      },
      response,
    },
  });
  expect(created.status(), `orderId=${orderId} の失敗のスタブの登録`).toBe(201);
  return async () => {
    const removed = await request.delete(`${paymentGatewayUrl}/__admin/mappings/${id}`);
    expect(removed.ok(), `orderId=${orderId} の失敗のスタブ ${id} の削除`).toBe(true);
  };
}
