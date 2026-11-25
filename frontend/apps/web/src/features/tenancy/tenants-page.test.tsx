/* @vitest-environment jsdom */

import {
  getListMembershipsMockHandler,
  getSelectTenantMockHandler,
} from "@frontend/api/mocks/tenancy/tenancy.msw";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vite-plus/test";

import { server } from "@/testing/msw";
import { problemResponse } from "@/testing/orders";
import { renderRoute } from "@/testing/render-route";
import { goodsTenant, tenantNotSelected, tradingTenant } from "@/testing/tenancy";

describe("テナントを選ぶ画面", () => {
  it("所属するテナントの名前のボタンを出し、選ぶとそのテナントを送ってから、文書を / で読み直す", async () => {
    const selected: unknown[] = [];
    const assign = vi.fn<(url: string) => void>();
    vi.stubGlobal("location", {
      href: globalThis.location.href,
      origin: globalThis.location.origin,
      assign,
    });
    server.use(
      getListMembershipsMockHandler({ items: [tradingTenant, goodsTenant] }),
      getSelectTenantMockHandler(async ({ request }) => {
        selected.push(await request.json());
      }),
    );
    const { user } = await renderRoute("/tenants");

    await expect(
      screen.findByRole("heading", { level: 1, name: "テナントを選んでください" }),
    ).resolves.toBeTruthy();
    expect(screen.getByRole("button", { name: "サンプル商事" })).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "サンプル物産" }));

    await expect.poll(() => assign.mock.calls).toStrictEqual([["/"]]);
    expect(selected).toStrictEqual([{ tenantId: goodsTenant.tenantId }]);
  });

  it("所属がないときは、所属を待っていることを伝え、テナントのボタンを出さない", async () => {
    server.use(getListMembershipsMockHandler({ items: [] }));
    await renderRoute("/tenants");

    await expect(
      screen.findByRole("heading", { level: 1, name: "所属を待っています" }),
    ).resolves.toBeTruthy();
    expect(screen.getByText(/所属が登録されたあとにこの画面を開き直すと/u)).toBeTruthy();
    expect(screen.queryByRole("list")).toBeNull();
    expect(screen.getByRole("button", { name: "ログアウト" })).toBeTruthy();
  });

  it("選べなかったときは、backend の title を出して画面に残る", async () => {
    server.use(
      getListMembershipsMockHandler({ items: [tradingTenant] }),
      http.put("*/api/current-tenant", () =>
        problemResponse(422, "テナントに所属していません", {
          type: "/problems/tenancy/not-tenant-member",
        }),
      ),
    );
    const { router, user } = await renderRoute("/tenants");

    await user.click(await screen.findByRole("button", { name: "サンプル商事" }));

    await expect(screen.findByText("テナントに所属していません")).resolves.toBeTruthy();
    expect(router.state.location.pathname).toBe("/tenants");
  });

  it("problem でない失敗では、カタログの文言を出し、Idempotency-Key のない選択を自動で再送しない", async () => {
    let requests = 0;
    server.use(
      getListMembershipsMockHandler({ items: [tradingTenant] }),
      http.put("*/api/current-tenant", () => {
        requests += 1;
        return new HttpResponse("Bad Gateway", { status: 502 });
      }),
    );
    const { user } = await renderRoute("/tenants");

    await user.click(await screen.findByRole("button", { name: "サンプル商事" }));

    await expect(screen.findByText("テナントを選べませんでした。")).resolves.toBeTruthy();
    expect(requests).toBe(1);
  });
});

describe("業務の画面のテナント", () => {
  it("選んだテナントの名前を出す", async () => {
    await renderRoute("/", { locale: "en" });

    await expect(screen.findByText("Tenant: サンプル商事")).resolves.toBeTruthy();
  });

  it("テナントを選ぶ前に業務の画面を開くと、テナントを選ぶ画面へ移る", async () => {
    server.use(
      http.get("*/api/current-tenant", tenantNotSelected),
      getListMembershipsMockHandler({ items: [tradingTenant, goodsTenant] }),
    );
    const { router } = await renderRoute("/orders");

    await expect(
      screen.findByRole("heading", { level: 1, name: "テナントを選んでください" }),
    ).resolves.toBeTruthy();
    expect(router.state.location.pathname).toBe("/tenants");
  });

  it("選んだテナントを読めなければ、再試行できる一般のエラーを出す", async () => {
    server.use(http.get("*/api/current-tenant", () => problemResponse(500, "サーバー内部エラー")));
    await renderRoute("/");

    await expect(screen.findByRole("button", { name: "再試行" })).resolves.toBeTruthy();
    expect(screen.queryByRole("link", { name: "テナントを選ぶ" })).toBeNull();
  });

  it("業務の API がテナントを選ぶ前の 403 を返したら、backend の title とテナントを選ぶ画面へのリンクを出す", async () => {
    server.use(http.get("*/api/products", tenantNotSelected));
    await renderRoute("/orders/new");

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("テナントが選ばれていません");
    expect(alert.textContent).toContain("テナントを選び直してください");
    expect(screen.getByRole("link", { name: "テナントを選ぶ" }).getAttribute("href")).toBe(
      "/tenants",
    );
    expect(screen.queryByRole("button", { name: "再試行" })).toBeNull();
  });

  it("業務の API が権限不足の 403 を返したら、再試行できる一般のエラーを出す", async () => {
    server.use(http.get("*/api/products", () => problemResponse(403, "アクセス権限がありません")));
    await renderRoute("/orders/new");

    await expect(screen.findByRole("button", { name: "再試行" })).resolves.toBeTruthy();
    expect(screen.queryByRole("link", { name: "テナントを選ぶ" })).toBeNull();
  });
});
