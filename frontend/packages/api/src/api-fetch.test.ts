// oxlint-disable max-lines -- apiFetch の応答、CSRF の header、401 の遷移、再試行の判定を 1 つの module の test に集めるため、行数で分割しない。
// oxlint-disable-next-line import/no-nodejs-modules -- 再送の発火条件の type を、コミット済みの OpenAPI 契約と突き合わせる。
import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

import { server } from "./testing/msw";

// Node の fetch は相対 URL を解決しないため、絶対 URL にする。
const baseUrl = "http://localhost";
let api = await import("./api-fetch");
// resetModules の後に読み直さないと、api-fetch が投げる ApiProblemError と別の class になる。
let { ApiProblemError } = await import("./api-error");

async function fetchError(path: string): Promise<unknown> {
  try {
    await api.apiFetch(`${baseUrl}${path}`, {});
  } catch (error) {
    return error;
  }
  throw new Error("apiFetch did not throw");
}

const csrfTokenInvalidType = "/problems/csrf-token-invalid";
const csrfProblem = { type: csrfTokenInvalidType, title: "Invalid", status: 403 };
const permissionProblem = { type: "about:blank", title: "Forbidden", status: 403 };

function forbidden(problem: object) {
  return HttpResponse.json(problem, {
    status: 403,
    headers: { "Content-Type": "application/problem+json" },
  });
}

async function post(): Promise<unknown> {
  try {
    return await api.apiFetch(`${baseUrl}/api/items`, { method: "POST", body: "{}" });
  } catch (error) {
    return error;
  }
}

describe("api-fetch", () => {
  beforeEach(async () => {
    vi.resetModules();
    api = await import("./api-fetch");
    ({ ApiProblemError } = await import("./api-error"));
    vi.stubGlobal("location", { href: `${baseUrl}/`, origin: baseUrl });
    // Node には document がないため、Cookie のない document を置く。
    vi.stubGlobal("document", { cookie: "" });
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

      expect(error).toBeInstanceOf(ApiProblemError);
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

      expect(error).toBeInstanceOf(ApiProblemError);
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

      expect(error).toBeInstanceOf(ApiProblemError);
      expect(error).toMatchObject({ status: 500, problem: undefined });
    });
  });

  describe("CSRF の header", () => {
    const cookieValue = "0b1e5c1a-4f0e-4c55-9d8e-2f1a3b4c5d6e";
    const received: Headers[] = [];

    beforeEach(() => {
      received.length = 0;
      server.use(
        // 別の origin への要求も受けるよう、origin を問わない path にする。
        http.all("*/api/echo", ({ request }) => {
          received.push(request.headers);
          // oxlint-disable-next-line unicorn/no-null -- 204 は本文を持てない。
          return new HttpResponse(null, { status: 204 });
        }),
      );
    });

    async function send(init: RequestInit, origin = baseUrl): Promise<Headers | undefined> {
      await api.apiFetch(`${origin}/api/echo`, init);
      return received.at(-1);
    }

    it.each(["POST", "PUT", "PATCH", "DELETE", "post", "patch"])(
      "%s に Cookie と同じ値の X-XSRF-TOKEN を付ける",
      async (method) => {
        vi.stubGlobal("document", { cookie: `other=1; __Host-XSRF-TOKEN=${cookieValue}` });

        const headers = await send({ method });

        expect(headers?.get("X-XSRF-TOKEN")).toBe(cookieValue);
      },
    );

    it.each([{ method: "GET" }, { method: "HEAD" }, {}])("%j には付けない", async (init) => {
      vi.stubGlobal("document", { cookie: `__Host-XSRF-TOKEN=${cookieValue}` });

      const headers = await send(init);

      expect(headers?.has("X-XSRF-TOKEN")).toBeFalsy();
    });

    it.each(["other=1", "__Host-XSRF-TOKEN="])(
      "Cookie が %j のときは付けずに送る",
      async (cookie) => {
        vi.stubGlobal("document", { cookie });

        const headers = await send({ method: "POST" });

        expect(headers?.has("X-XSRF-TOKEN")).toBeFalsy();
      },
    );

    it("別の origin への POST には付けない", async () => {
      vi.stubGlobal("document", { cookie: `__Host-XSRF-TOKEN=${cookieValue}` });

      const headers = await send({ method: "POST" }, "http://other.example");

      expect(headers?.has("X-XSRF-TOKEN")).toBeFalsy();
    });

    it("生成コードが渡す plain object の header を保つ", async () => {
      vi.stubGlobal("document", { cookie: `__Host-XSRF-TOKEN=${cookieValue}` });

      const headers = await send({
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });

      expect(headers?.get("Content-Type")).toBe("application/json");
      expect(headers?.get("X-XSRF-TOKEN")).toBe(cookieValue);
    });
  });

  describe("CSRF の 403 の再試行", () => {
    const tokens: (string | undefined)[] = [];

    // 応答の Set-Cookie と同じく、403 を返すたびにブラウザの Cookie を新しい値にする。
    // rotateCookie を false にすると、Cookie がある不一致の枝のように、403 でも Cookie を変えない。
    function respondWith(responses: (() => Response)[], rotateCookie = true) {
      server.use(
        http.post(`${baseUrl}/api/items`, ({ request }) => {
          tokens.push(request.headers.get("X-XSRF-TOKEN") ?? undefined);
          if (rotateCookie) {
            vi.stubGlobal("document", { cookie: `__Host-XSRF-TOKEN=fresh${tokens.length}` });
          }
          const respond = responses[tokens.length - 1];
          return respond === undefined ? new HttpResponse(undefined, { status: 500 }) : respond();
        }),
      );
    }

    beforeEach(() => {
      tokens.length = 0;
      vi.stubGlobal("document", { cookie: "__Host-XSRF-TOKEN=stale" });
    });

    it("CSRF の 403 を Cookie を読み直して 1 回だけ再送し、成功を返す", async () => {
      respondWith([
        () => forbidden(csrfProblem),
        () => new HttpResponse(undefined, { status: 204 }),
      ]);

      const result = await post();

      expect(result).toMatchObject({ status: 204 });
      expect(tokens).toStrictEqual(["stale", "fresh1"]);
    });

    it("CSRF の 403 が続くと 2 回で止め、2 回目の ApiProblemError を投げる", async () => {
      respondWith([
        () => forbidden(csrfProblem),
        () => forbidden(csrfProblem),
        () => forbidden(csrfProblem),
      ]);

      const error = await post();

      expect(error).toBeInstanceOf(ApiProblemError);
      expect(error).toMatchObject({ status: 403, problem: csrfProblem });
      expect(tokens).toHaveLength(2);
    });

    it("403 でも Cookie が変わらない不一致は、同じ token で 1 回だけ再送し、2 回目の 403 で終わる", async () => {
      respondWith([() => forbidden(csrfProblem), () => forbidden(csrfProblem)], false);

      const error = await post();

      expect(error).toBeInstanceOf(ApiProblemError);
      expect(error).toMatchObject({ status: 403, problem: csrfProblem });
      expect(tokens).toStrictEqual(["stale", "stale"]);
    });

    it("再送の発火条件の type は、コミット済みの OpenAPI 契約にある", () => {
      // 契約の type と食い違うと再送が静かに止まる。上の再送のテストは、同じ定数で本体の発火を確かめている。
      const contract = readFileSync(
        new URL("../../../../openapi/openapi.yaml", import.meta.url),
        "utf8",
      );

      expect(contract).toContain(csrfTokenInvalidType);
    });

    it("権限不足の 403 は再送せず、problem をそのまま ApiProblemError にする", async () => {
      respondWith([() => forbidden(permissionProblem)]);

      const error = await post();

      expect(error).toBeInstanceOf(ApiProblemError);
      expect(error).toMatchObject({ status: 403, problem: permissionProblem });
      expect(tokens).toHaveLength(1);
    });

    it.each([
      ["problem+json 以外", () => new HttpResponse("denied", { status: 403 })],
      [
        "壊れた problem+json",
        () =>
          new HttpResponse("{", {
            status: 403,
            headers: { "Content-Type": "application/problem+json" },
          }),
      ],
    ])("%s の 403 は再送しない", async (_name, response) => {
      respondWith([response]);

      const error = await post();

      expect(error).toMatchObject({ status: 403, problem: undefined });
      expect(tokens).toHaveLength(1);
    });
  });
});
