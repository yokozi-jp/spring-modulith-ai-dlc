import { Outlet } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

export function AppShell() {
  const { t } = useTranslation();

  return (
    <div className="min-h-svh bg-background text-foreground">
      <header className="border-b px-6 py-3 sm:px-10">
        <p className="font-semibold">{t("title")}</p>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
