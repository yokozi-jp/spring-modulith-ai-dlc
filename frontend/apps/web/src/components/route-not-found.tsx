import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { buttonVariants } from "@/components/ui/button";

export function RouteNotFound() {
  const { t } = useTranslation();

  return (
    <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10">
      <h1 className="text-2xl font-semibold">{t("notFoundHeading")}</h1>
      <p className="text-muted-foreground">{t("notFoundDescription")}</p>
      <Link to="/" className={buttonVariants({ variant: "outline" })}>
        {t("backToHome")}
      </Link>
    </section>
  );
}
