import { createFileRoute } from "@tanstack/react-router";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";

import { buttonVariants } from "@/components/ui/button";

function LoggedOutPage() {
  const { t } = useTranslation();

  useEffect(() => {
    document.title = t("title");
  }, [t]);

  return (
    <div className="min-h-svh bg-background text-foreground">
      <main>
        <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10">
          <h1 className="text-2xl font-semibold">{t("loggedOutHeading")}</h1>
          {/* ログインは backend の OAuth2 の入口なので、router の Link ではなく全画面遷移にする。 */}
          <a href="/oauth2/authorization/web" className={buttonVariants({ variant: "outline" })}>
            {t("loginAgain")}
          </a>
        </section>
      </main>
    </div>
  );
}

export const Route = createFileRoute("/logged-out")({ component: LoggedOutPage });
