/* @vitest-environment jsdom */

import { getListTenantMembersMockHandler } from "@frontend/api/mocks/tenancy/tenancy.msw";
import type { TenantMembersDetailsResponse } from "@frontend/api/models";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vite-plus/test";

import { server } from "@/testing/msw";
import { problemResponse } from "@/testing/orders";
import { renderRoute } from "@/testing/render-route";
import { tenantNotSelected } from "@/testing/tenancy";

const adminId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const memberId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

const members: TenantMembersDetailsResponse = {
  lockNo: 3,
  items: [
    { userId: adminId, role: "ADMIN" },
    { userId: memberId, role: "MEMBER" },
  ],
};

const memberPath = `*/api/current-tenant/members/${memberId}`;

describe("所属の管理の画面", () => {
  it("選んだテナントの利用者と役割を一覧にし、業務の画面から所属の管理へのリンクを出す", async () => {
    server.use(getListTenantMembersMockHandler(members));
    const { router, user } = await renderRoute("/");

    await user.click(await screen.findByRole("link", { name: "所属を管理する" }));

    await expect(
      screen.findByRole("heading", { level: 1, name: "所属の管理" }),
    ).resolves.toBeTruthy();
    expect(router.state.location.pathname).toBe("/members");
    expect(screen.getByText(adminId)).toBeTruthy();
    expect(screen.getByRole("combobox", { name: `利用者 ${adminId} の役割` })).toHaveProperty(
      "value",
      "ADMIN",
    );
    expect(screen.getByRole("combobox", { name: `利用者 ${memberId} の役割` })).toHaveProperty(
      "value",
      "MEMBER",
    );
  });

  it("役割を選ぶと、一覧で読んだ lockNo と選んだ役割を送り、変更を通知する", async () => {
    const sent: unknown[] = [];
    server.use(
      getListTenantMembersMockHandler(members),
      http.put(memberPath, async ({ request }) => {
        sent.push(await request.json());
        return new HttpResponse(undefined, { status: 204 });
      }),
    );
    const { user } = await renderRoute("/members");

    await user.selectOptions(
      await screen.findByRole("combobox", { name: `利用者 ${memberId} の役割` }),
      "ADMIN",
    );

    await expect(screen.findByText("役割を変更しました。")).resolves.toBeTruthy();
    expect(sent).toStrictEqual([{ role: "ADMIN", lockNo: 3 }]);
  });

  it("所属を外すと、一覧で読んだ lockNo を query で送り、変更を通知する", async () => {
    const lockNos: (string | null)[] = [];
    server.use(
      getListTenantMembersMockHandler(members),
      http.delete(memberPath, ({ request }) => {
        lockNos.push(new URL(request.url).searchParams.get("lockNo"));
        return new HttpResponse(undefined, { status: 204 });
      }),
    );
    const { user } = await renderRoute("/members");

    await user.click(await screen.findByRole("button", { name: `${memberId} の所属を外す` }));

    await expect(screen.findByText("所属を外しました。")).resolves.toBeTruthy();
    expect(lockNos).toStrictEqual(["3"]);
  });
});

describe("所属の管理の画面の失敗", () => {
  it("409 は backend の title と競合の説明を出し、自動で再送しない", async () => {
    let requests = 0;
    server.use(
      getListTenantMembersMockHandler(members),
      http.put(memberPath, () => {
        requests += 1;
        return problemResponse(409, "ほかの操作が先に更新しました", {
          type: "/problems/version-conflict",
        });
      }),
    );
    const { user } = await renderRoute("/members");

    await user.selectOptions(
      await screen.findByRole("combobox", { name: `利用者 ${memberId} の役割` }),
      "ADMIN",
    );

    await expect(screen.findByText("ほかの操作が先に更新しました")).resolves.toBeTruthy();
    expect(screen.getByText(/ほかの操作で所属が更新されました/u)).toBeTruthy();
    expect(requests).toBe(1);
  });

  it("すでに所属していない利用者への操作の 404 は、最新の一覧を表示していることを伝える", async () => {
    server.use(
      getListTenantMembersMockHandler(members),
      http.delete(memberPath, () => problemResponse(404, "見つかりません")),
    );
    const { user } = await renderRoute("/members");

    await user.click(await screen.findByRole("button", { name: `${memberId} の所属を外す` }));

    await expect(screen.findByText("見つかりません")).resolves.toBeTruthy();
    expect(screen.getByText(/すでにテナントに所属していません/u)).toBeTruthy();
  });

  it("最後の管理者を下げる操作の 422 は、管理者を残す必要があることを伝える", async () => {
    server.use(
      getListTenantMembersMockHandler({
        lockNo: 1,
        items: [{ userId: adminId, role: "ADMIN" }],
      }),
      http.put(`*/api/current-tenant/members/${adminId}`, () =>
        problemResponse(422, "テナントには管理者が必要です", {
          type: "/problems/tenancy/last-admin",
        }),
      ),
    );
    const { user } = await renderRoute("/members");

    await user.selectOptions(
      await screen.findByRole("combobox", { name: `利用者 ${adminId} の役割` }),
      "MEMBER",
    );

    await expect(screen.findByText("テナントには管理者が必要です")).resolves.toBeTruthy();
    expect(screen.getByText(/管理者を 1 人以上残す必要があります/u)).toBeTruthy();
  });

  it("problem でない失敗では、一般の文言を出す", async () => {
    server.use(
      getListTenantMembersMockHandler(members),
      http.delete(memberPath, () => new HttpResponse("Bad Gateway", { status: 502 })),
    );
    const { user } = await renderRoute("/members");

    await user.click(await screen.findByRole("button", { name: `${memberId} の所属を外す` }));

    await expect(screen.findByText("操作を完了できませんでした")).resolves.toBeTruthy();
    expect(screen.getByText("時間をおいてもう一度お試しください。")).toBeTruthy();
  });

  it("管理者でない利用者の 403 は、管理者だけの画面だと伝え、再試行を出さない", async () => {
    server.use(
      http.get("*/api/current-tenant/members", () =>
        problemResponse(403, "アクセス権限がありません"),
      ),
    );
    await renderRoute("/members");

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("管理者だけが使える画面です");
    expect(screen.queryByRole("button", { name: "再試行" })).toBeNull();
  });

  it("一覧を読めない 403 以外の失敗は、再試行できる一般のエラーを出す", async () => {
    server.use(
      http.get("*/api/current-tenant/members", () => problemResponse(500, "サーバー内部エラー")),
    );
    await renderRoute("/members");

    await expect(screen.findByRole("button", { name: "再試行" })).resolves.toBeTruthy();
    expect(screen.queryByText("管理者だけが使える画面です")).toBeNull();
  });

  it("テナントを選んでいない 403 は、管理者だけの画面ではなく、テナントを選び直す画面へ案内する", async () => {
    server.use(http.get("*/api/current-tenant/members", () => tenantNotSelected()));
    await renderRoute("/members");

    await expect(screen.findByRole("link", { name: "テナントを選ぶ" })).resolves.toBeTruthy();
    expect(screen.queryByText("管理者だけが使える画面です")).toBeNull();
  });

  it("自分の役割を下げて、その後の一覧の再取得が 403 になったら、操作を残さず管理者だけの画面を出す", async () => {
    let reads = 0;
    server.use(
      http.get("*/api/current-tenant/members", () => {
        reads += 1;
        return reads === 1
          ? HttpResponse.json(members)
          : problemResponse(403, "アクセス権限がありません");
      }),
      http.put(
        `*/api/current-tenant/members/${adminId}`,
        () => new HttpResponse(undefined, { status: 204 }),
      ),
    );
    const { user } = await renderRoute("/members");

    await user.selectOptions(
      await screen.findByRole("combobox", { name: `利用者 ${adminId} の役割` }),
      "MEMBER",
    );

    await expect(screen.findByText("管理者だけが使える画面です")).resolves.toBeTruthy();
    expect(screen.queryByRole("combobox")).toBeNull();
  });
});
