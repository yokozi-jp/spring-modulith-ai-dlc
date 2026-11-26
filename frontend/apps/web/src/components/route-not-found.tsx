import { buttonVariants } from "@frontend/ui/button";
import { Link, useMatch } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

export function RouteNotFound() {
  const { t } = useTranslation();
  // アプリシェルの外（どの route にも一致しない URL）では main の landmark がほかにないので、自分で持つ。
  const Landmark =
    useMatch({ from: "/_authenticated", shouldThrow: false }) === undefined ? "main" : "section";

  return (
    <Landmark className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10">
      <h1 className="text-2xl font-semibold">{t("notFoundHeading")}</h1>
      <p className="text-muted-foreground">{t("notFoundDescription")}</p>
      <Link to="/" className={buttonVariants({ variant: "outline" })}>
        {t("backToHome")}
      </Link>
    </Landmark>
  );
}
