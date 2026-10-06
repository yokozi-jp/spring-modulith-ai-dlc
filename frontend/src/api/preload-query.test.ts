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
});
