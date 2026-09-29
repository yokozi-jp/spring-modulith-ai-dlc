import "./style.css";
import { CSPProvider } from "@base-ui/react/csp-provider";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { createRoot } from "react-dom/client";
import { routeTree } from "./routeTree.gen";

const router = createRouter({ routeTree });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

const app = document.querySelector<HTMLDivElement>("#app");
if (!app) throw new Error("#app element not found");

createRoot(app).render(
  <CSPProvider disableStyleElements>
    <RouterProvider router={router} />
  </CSPProvider>,
);
