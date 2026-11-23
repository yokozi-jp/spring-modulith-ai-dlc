import { clearReturnTo } from "@frontend/api/login-return-to";
import { buttonVariants } from "@frontend/ui/button";
import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

function LoggedOutPage() {
  const { t } = useTranslation();

  return (
    <div className="min-h-svh bg-background text-foreground">
      <title>{t("title")}</title>
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

// 利用者をまたいで戻り先を残さない。
function beforeLoad() {
  clearReturnTo();
}

export const Route = createFileRoute("/logged-out")({ beforeLoad, component: LoggedOutPage });
