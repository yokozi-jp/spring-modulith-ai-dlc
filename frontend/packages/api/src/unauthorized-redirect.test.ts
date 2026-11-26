import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

const loginPath = "/oauth2/authorization/web";
const assign = vi.fn<(url: string) => void>();

// fallback の memory の値はモジュールの変数なので、テストごとに読み直した module から作る。
async function loadRedirect() {
  vi.resetModules();
  const { ApiProblemError } = await import("./api-error");
  const { createUnauthorizedRedirect } = await import("./unauthorized-redirect");
  return { ApiProblemError, redirect: createUnauthorizedRedirect() };
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

describe("createUnauthorizedRedirect", () => {
  beforeEach(() => {
    assign.mockReset();
    vi.stubGlobal("location", { assign, href: "http://localhost/", origin: "http://localhost" });
    const items = new Map<string, string>();
    vi.stubGlobal("sessionStorage", {
      // oxlint-disable-next-line unicorn/no-null -- Storage.getItem の仕様どおり、値がなければ null を返す。
      getItem: (key: string) => items.get(key) ?? null,
      setItem: (key: string, value: string) => items.set(key, value),
      removeItem: (key: string) => items.delete(key),
    });
  });

  it("401 でログインへ 1 回だけ遷移する", async () => {
    const { ApiProblemError, redirect } = await loadRedirect();
    const error = new ApiProblemError(401, undefined);

    redirect.onError(error);
    redirect.onError(error);

    expect(assign).toHaveBeenCalledExactlyOnceWith(loginPath);
  });

  it("遷移の前に、いまの path、query、hash を戻り先として保存する", async () => {
    const { ApiProblemError, redirect } = await loadRedirect();
    vi.stubGlobal("location", {
      assign,
      href: "http://localhost/orders/new?a=1#x",
      origin: "http://localhost",
      pathname: "/orders/new",
      search: "?a=1",
      hash: "#x",
    });

    redirect.onError(new ApiProblemError(401, undefined));

    const { takeReturnTo } = await import("./login-return-to");
    expect(takeReturnTo()).toBe("/orders/new?a=1#x");
  });

  it("30 秒以内の 2 回目の 401 は、保存した戻り先を書き換えない", async () => {
    const { ApiProblemError, redirect } = await loadRedirect();
    const error = new ApiProblemError(401, undefined);
    vi.stubGlobal("location", {
      assign,
      origin: "http://localhost",
      pathname: "/orders/1",
      search: "",
      hash: "",
    });
    redirect.onError(error);
    vi.stubGlobal("location", {
      assign,
      origin: "http://localhost",
      pathname: "/orders/2",
      search: "",
      hash: "",
    });

    redirect.onError(error);

    const { takeReturnTo } = await import("./login-return-to");
    expect(takeReturnTo()).toBe("/orders/1");
  });

  it("30 秒を過ぎた 401 では再び遷移する", async () => {
    const { ApiProblemError, redirect } = await loadRedirect();
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2025-01-01T00:00:00Z"));
    const error = new ApiProblemError(401, undefined);

    redirect.onError(error);
    vi.setSystemTime(new Date("2025-01-01T00:00:29.999Z"));
    redirect.onError(error);
    vi.setSystemTime(new Date("2025-01-01T00:00:30Z"));
    redirect.onError(error);

    expect(assign).toHaveBeenCalledTimes(2);
  });

  it("401 以外と ApiProblemError 以外では遷移しない", async () => {
    const { ApiProblemError, redirect } = await loadRedirect();

    redirect.onError(new ApiProblemError(403, undefined));
    redirect.onError(new TypeError("Failed to fetch"));
    redirect.onError(Object.assign(new Error("x"), { status: 401 }));

    expect(assign).not.toHaveBeenCalled();
  });

  it("sessionStorage が例外を投げても遷移を 1 回に抑える", async () => {
    const { ApiProblemError, redirect } = await loadRedirect();
    stubThrowingSessionStorage();
    const error = new ApiProblemError(401, undefined);

    redirect.onError(error);
    redirect.onError(error);

    expect(assign).toHaveBeenCalledExactlyOnceWith(loginPath);
  });

  it("数値でない保存値は遷移していないとして扱う", async () => {
    const { ApiProblemError, redirect } = await loadRedirect();
    globalThis.sessionStorage.setItem("api.loginRedirectedAt", "not-a-number");

    redirect.onError(new ApiProblemError(401, undefined));

    expect(assign).toHaveBeenCalledExactlyOnceWith(loginPath);
  });

  describe("同じ文書で API が成功した後", () => {
    it("401 は、ログインではなく有効期限切れの画面へ移り、戻り先を保存する", async () => {
      const { ApiProblemError, redirect } = await loadRedirect();
      vi.stubGlobal("location", {
        assign,
        origin: "http://localhost",
        pathname: "/orders/new",
        search: "?a=1",
        hash: "",
      });
      redirect.onSuccess();

      redirect.onError(new ApiProblemError(401, undefined));

      expect(assign).toHaveBeenCalledExactlyOnceWith("/session-expired");
      const { takeReturnTo } = await import("./login-return-to");
      expect(takeReturnTo()).toBe("/orders/new?a=1");
    });

    it("401 は、30 秒以内にログインへ遷移していても有効期限切れの画面へ移る", async () => {
      const { ApiProblemError, redirect } = await loadRedirect();
      const error = new ApiProblemError(401, undefined);
      redirect.onError(error);
      redirect.onSuccess();

      redirect.onError(error);

      expect(assign.mock.calls).toStrictEqual([[loginPath], ["/session-expired"]]);
    });

    it("有効期限切れの画面への遷移は、ログインへの遷移の時刻を記録しない", async () => {
      const { ApiProblemError, redirect } = await loadRedirect();
      redirect.onSuccess();

      redirect.onError(new ApiProblemError(401, undefined));

      expect(assign).toHaveBeenCalledExactlyOnceWith("/session-expired");
      expect(globalThis.sessionStorage.getItem("api.loginRedirectedAt")).toBeNull();
    });

    it("sessionStorage が例外を投げても、有効期限切れの画面へ移る", async () => {
      const { ApiProblemError, redirect } = await loadRedirect();
      stubThrowingSessionStorage();
      redirect.onSuccess();

      redirect.onError(new ApiProblemError(401, undefined));

      expect(assign).toHaveBeenCalledExactlyOnceWith("/session-expired");
    });
  });
});
