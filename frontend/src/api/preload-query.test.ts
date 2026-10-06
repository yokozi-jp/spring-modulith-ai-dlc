import { describe, expect, it, vi } from "vite-plus/test";

import { createTestQueryClient } from "@/testing/query-client";

import { preloadQuery } from "./preload-query";

describe("preloadQuery", () => {
  it("取得した data を cache に入れ、cache にあれば取得し直さない", async () => {
    const queryClient = createTestQueryClient();
    const queryFn = vi.fn<() => Promise<{ data: string }>>(() =>
      Promise.resolve({ data: "loaded" }),
    );
    const options = { queryKey: ["/api/items"], queryFn };

    await expect(preloadQuery(queryClient, options)).resolves.toStrictEqual({ data: "loaded" });
    await expect(preloadQuery(queryClient, options)).resolves.toStrictEqual({ data: "loaded" });

    expect(queryFn).toHaveBeenCalledOnce();
    expect(queryClient.getQueryData(["/api/items"])).toStrictEqual({ data: "loaded" });
  });

  it("無効化された cache は取得し直す", async () => {
    const queryClient = createTestQueryClient();
    let count = 0;
    const queryFn = vi.fn<() => Promise<{ data: number }>>(() => {
      count += 1;
      return Promise.resolve({ data: count });
    });
    const options = { queryKey: ["/api/items"], queryFn };

    await preloadQuery(queryClient, options);
    // observer がないので、無効化しても取得し直さず印だけが付く。
    await queryClient.invalidateQueries();

    await expect(preloadQuery(queryClient, options)).resolves.toStrictEqual({ data: 2 });
    expect(queryFn).toHaveBeenCalledTimes(2);
  });
});
