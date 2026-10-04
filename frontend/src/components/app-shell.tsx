import { Outlet } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

// ponytail: render 時に Cookie を読む。token はログインとログアウトの全画面遷移でしか入れ替わらないため。
// csrf.spa() はフォームの _csrf を XorCsrfTokenRequestAttributeHandler で検証するので、Cookie の値をマスクして送る。
// header（X-XSRF-TOKEN）で送るならマスクは要らない。
function maskedCsrfToken() {
  const token =
    document.cookie
      .split("; ")
      .find((cookie) => cookie.startsWith("XSRF-TOKEN="))
      ?.slice("XSRF-TOKEN=".length) ?? "";
  const bytes = new TextEncoder().encode(token);
  const random = crypto.getRandomValues(new Uint8Array(bytes.length));
  // oxlint-disable-next-line eslint/no-bitwise -- Spring Security のマスクは XOR で定義されている。
  const xored = bytes.map((byte, index) => byte ^ (random[index] ?? 0));
  return btoa(String.fromCodePoint(...random, ...xored))
    .replaceAll("+", "-")
    .replaceAll("/", "_");
}

export function AppShell() {
  const { t } = useTranslation();

  return (
    <div className="min-h-svh bg-background text-foreground">
      <header className="flex items-center justify-between border-b px-6 py-3 sm:px-10">
        <p className="font-semibold">{t("title")}</p>
        <form method="post" action="/logout">
          <input type="hidden" name="_csrf" value={maskedCsrfToken()} />
          <Button type="submit" variant="outline" size="sm">
            {t("logout")}
          </Button>
        </form>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
