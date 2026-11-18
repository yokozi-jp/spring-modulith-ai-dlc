import type { QueryClient } from "@tanstack/react-query";
import { QueryClientProvider } from "@tanstack/react-query";
import { createMemoryHistory, createRouter, RouterProvider } from "@tanstack/react-router";
import type { AnyRouter } from "@tanstack/react-router";
import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { UserEvent } from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { vi } from "vite-plus/test";

import { i18n } from "@/i18n";
import { routerDefaults } from "@/router-defaults";
import { routeTree } from "@/routeTree.gen";

import { createTestQueryClient } from "./query-client";

/** main.tsx と同じ provider と router の既定値で、path の route を loader から描画する。 */
export async function renderRoute(
  path: string,
  options?: { locale?: "ja" | "en" },
): Promise<{ router: AnyRouter; queryClient: QueryClient; user: UserEvent }> {
  await i18n.changeLanguage(options?.locale ?? "ja");
  // jsdom は scrollTo を実装していない（scrollRestoration が呼ぶ）。
  vi.spyOn(globalThis, "scrollTo").mockReturnValue();
  const queryClient = createTestQueryClient();
  const router = createRouter({
    history: createMemoryHistory({ initialEntries: [path] }),
    routeTree,
    context: { queryClient },
    ...routerDefaults,
  });
  // 描画の後に呼ぶと Transitioner の load と重なり loader が 2 回走るため、描画の前に最初の loader を終える。
  await router.load();
  // docs/frontend/testing.md のとおり、render の前に setup する。
  const user = userEvent.setup();
  render(
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </I18nextProvider>,
  );
  return { router, queryClient, user };
}
