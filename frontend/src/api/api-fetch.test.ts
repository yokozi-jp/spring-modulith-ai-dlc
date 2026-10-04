import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vite-plus/test";

// Node の fetch は相対 URL を解決しないため、絶対 URL にする。
const baseUrl = "http://localhost";
const loginPath = "/oauth2/authorization/web";
// MSW の server はこのファイルだけで使う。2 つ目の MSW のテストを書くときに src/testing/msw へ移す。
const server = setupServer();
const assign = vi.fn<(url: string) => void>();
// fallback の memory の値はモジュールの変数なので、テストごとに読み直す（beforeEach）。
let api = await import("./api-fetch");

function stubSessionStorage() {
  const items = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    // Storage.getItem の仕様どおり、値がなければ null を返す。
    // oxlint-disable-next-line unicorn/no-null
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => items.set(key, value),
    removeItem: (key: string) => items.delete(key),
  });
}

function failStorage(): never {
  throw new Error("storage is disabled");
}

function stubThrowingSessionStorage() {
  vi.stubGlobal("sessionStorage", {
    getItem: failStorage,
    setItem: failStorage,
    removeItem: failStorage,
  });
}

async function fetchError(path: string): Promise<unknown> {
  try {
    await api.apiFetch(`${baseUrl}${path}`, {});
  } catch (error) {
    return error;
  }
  throw new Error("apiFetch did not throw");
}

describe("api-fetch", () => {
  beforeAll(() => {
    server.listen({ onUnhandledRequest: "error" });
  });

  beforeEach(async () => {
    vi.resetModules();
    api = await import("./api-fetch");
    assign.mockReset();
    vi.stubGlobal("location", { assign });
  });

  afterEach(() => {
    server.resetHandlers();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  afterAll(() => {
    server.close();
  });

  describe("apiFetch", () => {
    it("2xx の JSON を data、status、headers で返す", async () => {
      server.use(http.get(`${baseUrl}/api/items`, () => HttpResponse.json({ id: 1 })));

      const response = await api.apiFetch<{ data: unknown; status: number; headers: Headers }>(
        `${baseUrl}/api/items`,
        {},
      );

      expect(response.data).toStrictEqual({ id: 1 });
      expect(response.status).toBe(200);
      expect(response.headers.get("Content-Type")).toBe("application/json");
    });

    it("204 で data を undefined にする", async () => {
      server.use(
        http.delete(`${baseUrl}/api/items/1`, () => new HttpResponse(undefined, { status: 204 })),
      );

      const response = await api.apiFetch<{ data: unknown; status: number }>(
        `${baseUrl}/api/items/1`,
        { method: "DELETE" },
      );

      expect(response.data).toBeUndefined();
      expect(response.status).toBe(204);
    });

    it("本文が空の 201 で data を undefined にし、Location を読める", async () => {
      // body が null の応答は空の stream の経路を通らないため、空文字列で作る。
      server.use(
        http.post(
          `${baseUrl}/api/items`,
          () => new HttpResponse("", { status: 201, headers: { Location: "/api/x/1" } }),
        ),
      );

      const response = await api.apiFetch<{ data: unknown; status: number; headers: Headers }>(
        `${baseUrl}/api/items`,
        { method: "POST" },
      );

      expect(response.data).toBeUndefined();
      expect(response.status).toBe(201);
      expect(response.headers.get("Location")).toBe("/api/x/1");
    });

    it("problem+json の 4xx を problem 付きの ApiProblemError にする", async () => {
      const problem = { type: "/problems/validation-error", title: "Invalid", status: 400 };
      server.use(
        http.get(`${baseUrl}/api/items`, () =>
          HttpResponse.json(problem, {
            status: 400,
            headers: { "Content-Type": "application/problem+json" },
          }),
        ),
      );

      const error = await fetchError("/api/items");

      expect(error).toBeInstanceOf(api.ApiProblemError);
      expect(error).toMatchObject({ status: 400, problem });
    });

    it("problem+json 以外の本文では problem を undefined にする", async () => {
      server.use(
        http.get(
          `${baseUrl}/api/items`,
          () =>
            new HttpResponse("<html>Bad Gateway</html>", {
              status: 502,
              headers: { "Content-Type": "text/html" },
            }),
        ),
      );

      const error = await fetchError("/api/items");

      expect(error).toBeInstanceOf(api.ApiProblemError);
      expect(error).toMatchObject({ status: 502, problem: undefined });
    });

    it("壊れた problem+json では problem を undefined にする", async () => {
      server.use(
        http.get(
          `${baseUrl}/api/items`,
          () =>
            new HttpResponse("{", {
              status: 500,
              headers: { "Content-Type": "application/problem+json" },
            }),
        ),
      );

      const error = await fetchError("/api/items");

      expect(error).toBeInstanceOf(api.ApiProblemError);
      expect(error).toMatchObject({ status: 500, problem: undefined });
    });
  });

  describe("redirectToLoginOnUnauthorized", () => {
    it("401 でログインへ 1 回だけ遷移する", () => {
      stubSessionStorage();
      const error = new api.ApiProblemError(401, undefined);

      api.redirectToLoginOnUnauthorized(error);
      api.redirectToLoginOnUnauthorized(error);

      expect(assign).toHaveBeenCalledExactlyOnceWith(loginPath);
    });

    it("30 秒を過ぎた 401 では再び遷移する", () => {
      stubSessionStorage();
      vi.useFakeTimers();
      vi.setSystemTime(new Date("2025-01-01T00:00:00Z"));
      const error = new api.ApiProblemError(401, undefined);

      api.redirectToLoginOnUnauthorized(error);
      vi.setSystemTime(new Date("2025-01-01T00:00:29.999Z"));
      api.redirectToLoginOnUnauthorized(error);
      vi.setSystemTime(new Date("2025-01-01T00:00:30Z"));
      api.redirectToLoginOnUnauthorized(error);

      expect(assign).toHaveBeenCalledTimes(2);
    });

    it("401 以外と ApiProblemError 以外では遷移しない", () => {
      stubSessionStorage();

      api.redirectToLoginOnUnauthorized(new api.ApiProblemError(403, undefined));
      api.redirectToLoginOnUnauthorized(new TypeError("Failed to fetch"));
      api.redirectToLoginOnUnauthorized(Object.assign(new Error("x"), { status: 401 }));

      expect(assign).not.toHaveBeenCalled();
    });

    it("sessionStorage が例外を投げても遷移を 1 回に抑える", () => {
      stubThrowingSessionStorage();
      const error = new api.ApiProblemError(401, undefined);

      api.redirectToLoginOnUnauthorized(error);
      api.redirectToLoginOnUnauthorized(error);

      expect(assign).toHaveBeenCalledExactlyOnceWith(loginPath);
    });

    it("数値でない保存値は遷移していないとして扱う", () => {
      stubSessionStorage();
      globalThis.sessionStorage.setItem("api.loginRedirectedAt", "not-a-number");

      api.redirectToLoginOnUnauthorized(new api.ApiProblemError(401, undefined));

      expect(assign).toHaveBeenCalledExactlyOnceWith(loginPath);
    });
  });

  describe("retryUnlessClientError", () => {
    it("408 と 429 以外の 4xx の ApiProblemError は再試行しない", () => {
      expect(api.retryUnlessClientError(0, new api.ApiProblemError(400, undefined))).toBeFalsy();
      expect(api.retryUnlessClientError(0, new api.ApiProblemError(499, undefined))).toBeFalsy();
    });

    it.each([408, 429])("%i の ApiProblemError は 3 回まで再試行する", (status) => {
      const error = new api.ApiProblemError(status, undefined);

      expect(api.retryUnlessClientError(2, error)).toBe(true);
      expect(api.retryUnlessClientError(3, error)).toBeFalsy();
    });

    it("5xx と通信の失敗は 3 回まで再試行する", () => {
      const serverError = new api.ApiProblemError(500, undefined);
      const networkError = new TypeError("Failed to fetch");

      expect(api.retryUnlessClientError(2, serverError)).toBe(true);
      expect(api.retryUnlessClientError(3, serverError)).toBeFalsy();
      expect(api.retryUnlessClientError(2, networkError)).toBe(true);
      expect(api.retryUnlessClientError(3, networkError)).toBeFalsy();
    });
  });
});
