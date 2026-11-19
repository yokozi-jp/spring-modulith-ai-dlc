import type { MembershipResponse } from "@frontend/api/models";
import { HttpResponse } from "msw";

// テナントの画面のテストが共有する固定の値（生成された MSW handler に渡す）。
export const tradingTenant: MembershipResponse = {
  tenantId: "44444444-4444-4444-8444-444444444444",
  tenantName: "サンプル商事",
};
export const goodsTenant: MembershipResponse = {
  tenantId: "55555555-5555-4555-8555-555555555555",
  tenantName: "サンプル物産",
};

/** テナントを選ぶ前に業務の API を呼んだときの、backend と同じ 403 の応答。 */
export function tenantNotSelected() {
  return HttpResponse.json(
    { type: "/problems/tenant-not-selected", title: "テナントが選ばれていません", status: 403 },
    { status: 403, headers: { "Content-Type": "application/problem+json" } },
  );
}
