import { ApiProblemError } from "@frontend/api/api-error";
import { apiFetch } from "@frontend/api/api-fetch";
import { MutationObserver, QueryObserver } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, onTestFinished, vi } from "vite-plus/test";

import { createGate } from "@/testing/gate";
import { server } from "@/testing/msw";
import { createTestQueryClient } from "@/testing/query-client";

// Node の fetch は相対 URL を解決しないため、絶対 URL にする。
const itemsUrl = "http://localhost/api/items";
const conflict = {
  type: "/problems/version-conflict",
  title: "Updated by another request",
  status: 409,
};

function respondToUpdate(status: 204 | 409) {
  if (status === 409) {
    return HttpResponse.json(conflict, {
      status,
      headers: { "Content-Type": "application/problem+json" },
    });
  }
  // oxlint-disable-next-line unicorn/no-null -- 204 は本文を持てない。
  return new HttpResponse(null, { status });
}

/** 最初の GET はすぐ返し、2 回目以降の GET（再取得）は release を呼ぶまで返さない。 */
function serveItemsHoldingRefetch(status: 204 | 409) {
  const refetch = createGate();
  let fetchCount = 0;
  server.use(
    http.get(itemsUrl, async () => {
      fetchCount += 1;
      if (fetchCount > 1) {
        await refetch.promise;
      }
      return HttpResponse.json({ fetchCount });
    }),
    http.post(itemsUrl, () => respondToUpdate(status)),
  );
  return { release: refetch.release, fetchCount: () => fetchCount };
}

/** 描画中の query を QueryObserver で作り、mutation を送って再取得が始まるまで待つ。 */
async function mutateWhileRefetchIsHeld(status: 204 | 409) {
  const items = serveItemsHoldingRefetch(status);
  const queryClient = createTestQueryClient();
  const query = new QueryObserver(queryClient, {
    queryKey: ["items"],
    queryFn: () => apiFetch<{ data: unknown }>(itemsUrl, {}),
  });
  onTestFinished(query.subscribe(vi.fn<() => void>()));
  await expect.poll(() => query.getCurrentResult().isSuccess).toBe(true);
  const mutation = new MutationObserver(queryClient, {
    mutationFn: () => apiFetch<{ status: number }>(itemsUrl, { method: "POST" }),
  });
  const result = mutation.mutate();
  await expect.poll(() => items.fetchCount()).toBe(2);
  return { query, mutation, result, release: items.release };
}

describe("createQueryClient", () => {
  const assign = vi.fn<(url: string) => void>();

  beforeEach(() => {
    // POST の apiFetch が CSRF の Cookie を読むため、Node に document を置く。
    vi.stubGlobal("document", { cookie: "" });
    // 更新系の apiFetch が送信先の origin を location と比べるため、Node に location を置く。
    vi.stubGlobal("location", { href: "http://localhost/", origin: "http://localhost", assign });
  });

  it("mutation の成功の後に描画中の query を取り直し、終わるまで pending を保つ", async () => {
    const { query, mutation, result, release } = await mutateWhileRefetchIsHeld(204);

    expect(mutation.getCurrentResult().isPending).toBe(true);
    release();

    await expect(result).resolves.toMatchObject({ status: 204 });
    expect(mutation.getCurrentResult().isPending).toBeFalsy();
    expect(query.getCurrentResult().data).toMatchObject({ data: { fetchCount: 2 } });
  });

  it("mutation の 409 の後にも描画中の query を取り直し、終わるまで pending を保つ", async () => {
    const { query, mutation, result, release } = await mutateWhileRefetchIsHeld(409);

    expect(mutation.getCurrentResult().isPending).toBe(true);
    release();

    await expect(result).rejects.toBeInstanceOf(ApiProblemError);
    await expect(result).rejects.toMatchObject({ status: 409, problem: conflict });
    expect(mutation.getCurrentResult().isPending).toBeFalsy();
    expect(query.getCurrentResult().data).toMatchObject({ data: { fetchCount: 2 } });
  });

  // 30 秒の遷移ガードの記録は module 内に残る。2 件目の 401 は遷移しないため、このファイルで 401 を確かめるのはこの 1 件にする。
  it("mutation の 401 でログインへ遷移する。描画中の query の再取得に頼らない", async () => {
    server.use(
      http.post(itemsUrl, () =>
        HttpResponse.json(
          { type: "about:blank", title: "Unauthorized", status: 401 },
          { status: 401, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    const mutation = new MutationObserver(createTestQueryClient(), {
      mutationFn: () => apiFetch<{ status: number }>(itemsUrl, { method: "POST" }),
    });

    await expect(mutation.mutate()).rejects.toMatchObject({ status: 401 });

    expect(assign).toHaveBeenCalledExactlyOnceWith("/oauth2/authorization/web");
  });
});
