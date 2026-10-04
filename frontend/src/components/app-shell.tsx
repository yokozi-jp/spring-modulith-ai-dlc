import { Outlet } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { maskedCsrfToken } from "@/lib/csrf";

export function AppShell() {
  const { t } = useTranslation();

  return (
    <div className="min-h-svh bg-background text-foreground">
      <header className="flex items-center justify-between border-b px-6 py-3 sm:px-10">
        <p className="font-semibold">{t("title")}</p>
        <form method="post" action="/logout">
          {/* ponytail: render 時に Cookie を読む。token はログインとログアウトの全画面遷移でしか入れ替わらないため。 */}
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
