import { beforeEach, describe, expect, it, vi } from "vite-plus/test";

const origin = "http://localhost";
let returnTo = await import("./login-return-to");

function stubSessionStorage() {
  const items = new Map<string, string>();
  vi.stubGlobal("sessionStorage", {
    // oxlint-disable-next-line unicorn/no-null -- Storage.getItem の仕様どおり、値がなければ null を返す。
    getItem: (key: string) => items.get(key) ?? null,
    setItem: (key: string, value: string) => items.set(key, value),
    removeItem: (key: string) => items.delete(key),
  });
}

function fail(): never {
  throw new Error("storage is disabled");
}

function stubThrowingSessionStorage() {
  vi.stubGlobal("sessionStorage", { getItem: fail, setItem: fail, removeItem: fail });
}

function stubLocation(pathname: string, search = "", hash = "") {
  vi.stubGlobal("location", { origin, pathname, search, hash });
}

describe("login-return-to", () => {
  beforeEach(async () => {
    vi.resetModules();
    returnTo = await import("./login-return-to");
    stubLocation("/");
  });

  describe("parseReturnTo", () => {
    it.each([
      ["/", "/"],
      ["/claims", "/claims"],
      ["/claims/1?tab=a#top", "/claims/1?tab=a#top"],
      ["/a//b", "/a//b"],
      [String.raw`/a\b`, "/a/b"],
    ])("同一オリジンの path として通す: %j", (value, expected) => {
      expect(returnTo.parseReturnTo(value)).toBe(expected);
    });

    it.each([
      "//evil.example",
      "//evil.example/path",
      "https://evil.example",
      "http://evil.example/",
      // oxlint-disable-next-line no-script-url -- 拒むことを確かめる入力。
      "javascript:alert(1)",
      String.raw`/\evil.example`,
      String.raw`\\evil.example`,
      "/\t/evil.example",
      "/\n/evil.example",
      "/.//evil.example",
      String.raw`/\^@`,
      "claims",
      "",
      " /claims",
    ])("拒む: %j", (value) => {
      expect(returnTo.parseReturnTo(value)).toBeUndefined();
    });

    it.each([undefined, 1, {}, ["/claims"]])("文字列でない値は拒む: %j", (value) => {
      expect(returnTo.parseReturnTo(value)).toBeUndefined();
    });
  });

  describe("saveReturnTo と takeReturnTo", () => {
    it("path、query、hash を保存し、1 回読むと消える", () => {
      stubSessionStorage();
      stubLocation("/orders/1", "?tab=a", "#top");

      returnTo.saveReturnTo();

      expect(returnTo.takeReturnTo()).toBe("/orders/1?tab=a#top");
      expect(returnTo.takeReturnTo()).toBeUndefined();
    });

    it("保存は 1 つだけで、後の保存が前を置き換える", () => {
      stubSessionStorage();
      stubLocation("/a");
      returnTo.saveReturnTo();
      stubLocation("/b");
      returnTo.saveReturnTo();

      expect(returnTo.takeReturnTo()).toBe("/b");
    });

    it("使えない値が保存されていれば undefined にして消す", () => {
      stubSessionStorage();
      globalThis.sessionStorage.setItem("auth.returnTo", "//evil.example");

      expect(returnTo.takeReturnTo()).toBeUndefined();
      expect(globalThis.sessionStorage.getItem("auth.returnTo")).toBeNull();
    });

    it("clearReturnTo は保存を使わずに消す", () => {
      stubSessionStorage();
      stubLocation("/orders/1");
      returnTo.saveReturnTo();

      returnTo.clearReturnTo();

      expect(returnTo.takeReturnTo()).toBeUndefined();
    });

    it("sessionStorage が例外を投げても memory で 1 回だけ読める", () => {
      stubThrowingSessionStorage();
      stubLocation("/orders/new");

      returnTo.saveReturnTo();

      expect(returnTo.takeReturnTo()).toBe("/orders/new");
      expect(returnTo.takeReturnTo()).toBeUndefined();
    });
  });
});
