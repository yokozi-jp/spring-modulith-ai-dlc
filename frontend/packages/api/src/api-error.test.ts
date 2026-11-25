// oxlint-disable-next-line import/no-nodejs-modules -- 判定に使う type を、コミット済みの OpenAPI 契約と突き合わせる。
import { readFileSync } from "node:fs";

import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

const loginPath = "/oauth2/authorization/web";
const assign = vi.fn<(url: string) => void>();
// fallback の memory の値はモジュールの変数なので、テストごとに読み直す（beforeEach）。
let api = await import("./api-error");

function stubSessionStorage() {
  const items = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    // oxlint-disable-next-line unicorn/no-null -- Storage.getItem の仕様どおり、値がなければ null を返す。
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

const csrfTokenInvalidType = "/problems/csrf-token-invalid";
const tenantNotSelectedType = "/problems/tenant-not-selected";
const csrfProblem = { type: csrfTokenInvalidType, title: "Invalid", status: 403 };
const permissionProblem = { type: "about:blank", title: "Forbidden", status: 403 };
const tenantProblem = { type: tenantNotSelectedType, title: "No tenant selected", status: 403 };
const inProgressType = "/problems/idempotency-key-in-progress";
const inProgressProblem = { type: inProgressType, title: "In progress", status: 409 };
const versionConflictProblem = {
  type: "/problems/version-conflict",
  title: "Updated by another request",
  status: 409,
};

describe("api-error", () => {
  beforeEach(async () => {
    vi.resetModules();
    api = await import("./api-error");
    assign.mockReset();
    vi.stubGlobal("location", { assign, href: "http://localhost/", origin: "http://localhost" });
  });

  describe("isTenantNotSelected", () => {
    it("テナントを選ぶ前の type の 403 を、テナントを選ぶ前の拒否とみなす", () => {
      expect(api.isTenantNotSelected(new api.ApiProblemError(403, tenantProblem))).toBe(true);
    });

    it.each([
      ["権限不足の 403", () => new api.ApiProblemError(403, permissionProblem)],
      ["CSRF の 403", () => new api.ApiProblemError(403, csrfProblem)],
      ["problem のない 403", () => new api.ApiProblemError(403, undefined)],
      ["同じ type の 400", () => new api.ApiProblemError(400, { ...tenantProblem, status: 400 })],
      ["通信の失敗", () => new Error("network")],
    ])("%s は、テナントを選ぶ前の拒否とみなさない", (_name, error) => {
      expect(api.isTenantNotSelected(error())).toBeFalsy();
    });

    it("判定に使う type は、コミット済みの OpenAPI 契約にある", () => {
      const contract = readFileSync(
        new URL("../../../../openapi/openapi.yaml", import.meta.url),
        "utf8",
      );

      expect(contract).toContain(tenantNotSelectedType);
      expect(contract).toContain(inProgressType);
    });
  });

  describe("Idempotency-Key を送らない操作", () => {
    it("コミット済みの OpenAPI 契約では selectTenant だけである。足すときは、生成 Hook の mutation に retry: false を渡す", () => {
      const contract = readFileSync(
        new URL("../../../../openapi/openapi.yaml", import.meta.url),
        "utf8",
      );

      // springdoc が operation の key を辞書順に書くので、x-idempotency-key の直前の operationId がその operation である。
      const exempt = [...contract.matchAll(/x-idempotency-key: none/gu)].map(
        ({ index }) =>
          [...contract.slice(0, index).matchAll(/operationId: (?<id>\w+)/gu)].at(-1)?.groups?.id,
      );

      expect(exempt).toStrictEqual(["selectTenant"]);
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

    it("遷移の前に、いまの path、query、hash を戻り先として保存する", async () => {
      stubSessionStorage();
      vi.stubGlobal("location", {
        assign,
        href: "http://localhost/orders/new?a=1#x",
        origin: "http://localhost",
        pathname: "/orders/new",
        search: "?a=1",
        hash: "#x",
      });

      api.redirectToLoginOnUnauthorized(new api.ApiProblemError(401, undefined));

      const { takeReturnTo } = await import("./login-return-to");
      expect(takeReturnTo()).toBe("/orders/new?a=1#x");
    });

    it("30 秒以内の 2 回目の 401 は、保存した戻り先を書き換えない", async () => {
      stubSessionStorage();
      const error = new api.ApiProblemError(401, undefined);
      vi.stubGlobal("location", {
        assign,
        origin: "http://localhost",
        pathname: "/orders/1",
        search: "",
        hash: "",
      });
      api.redirectToLoginOnUnauthorized(error);
      vi.stubGlobal("location", {
        assign,
        origin: "http://localhost",
        pathname: "/orders/2",
        search: "",
        hash: "",
      });

      api.redirectToLoginOnUnauthorized(error);

      const { takeReturnTo } = await import("./login-return-to");
      expect(takeReturnTo()).toBe("/orders/1");
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

  describe("retryIdempotentMutation", () => {
    it.each([
      ["通信の失敗", () => new TypeError("Failed to fetch")],
      ["5xx", () => new api.ApiProblemError(503, undefined)],
      ["408", () => new api.ApiProblemError(408, undefined)],
      ["429", () => new api.ApiProblemError(429, undefined)],
      ["処理中の 409", () => new api.ApiProblemError(409, inProgressProblem)],
    ])("%s は 3 回まで再試行する", (_name, error) => {
      expect(api.retryIdempotentMutation(2, error())).toBe(true);
      expect(api.retryIdempotentMutation(3, error())).toBeFalsy();
    });

    it.each([
      ["版の競合の 409", () => new api.ApiProblemError(409, versionConflictProblem)],
      ["problem のない 409", () => new api.ApiProblemError(409, undefined)],
      ["422", () => new api.ApiProblemError(422, undefined)],
      ["400", () => new api.ApiProblemError(400, undefined)],
      ["本文を読めない失敗", () => new SyntaxError("Unexpected token")],
    ])("%s は再試行しない", (_name, error) => {
      expect(api.retryIdempotentMutation(0, error())).toBeFalsy();
    });
  });

  describe("isIdempotencyKeyInProgress", () => {
    it("処理中の type でも 409 でなければ、処理中とみなさない", () => {
      expect(
        api.isIdempotencyKeyInProgress(
          new api.ApiProblemError(400, { ...inProgressProblem, status: 400 }),
        ),
      ).toBeFalsy();
    });
  });

  describe("isStateConflict", () => {
    it("処理中でない 409 だけを true にする", () => {
      expect(api.isStateConflict(new api.ApiProblemError(409, versionConflictProblem))).toBe(true);
      expect(api.isStateConflict(new api.ApiProblemError(409, undefined))).toBe(true);
      expect(api.isStateConflict(new api.ApiProblemError(409, inProgressProblem))).toBeFalsy();
      expect(api.isStateConflict(new api.ApiProblemError(422, undefined))).toBeFalsy();
      expect(api.isStateConflict(new TypeError("Failed to fetch"))).toBeFalsy();
    });
  });
});
