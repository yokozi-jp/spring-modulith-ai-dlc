import { vi } from "vite-plus/test";

export const csrfCookieValue = "4f0e1c2a-5b6d-4e7f-8a9b-0c1d2e3f4a5b";

export function stubCsrfCookie(): void {
  vi.spyOn(document, "cookie", "get").mockReturnValue(`__Host-XSRF-TOKEN=${csrfCookieValue}`);
}
