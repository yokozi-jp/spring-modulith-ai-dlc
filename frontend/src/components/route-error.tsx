import { useQueryErrorResetBoundary } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

// error.message は内部情報を含みうるため表示せず、カタログの文言だけを出す。
export function RouteError() {
  const { t } = useTranslation();
  const { reset } = useQueryErrorResetBoundary();
  const router = useRouter();

  return (
    <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10" role="alert">
      <h1 className="text-2xl font-semibold">{t("errorHeading")}</h1>
      <p className="text-muted-foreground">{t("errorDescription")}</p>
      <Button
        type="button"
        onClick={() => {
          reset();
          // loader の失敗は match の error 状態になり、invalidate の Promise は reject しない。
          void router.invalidate();
        }}
      >
        {t("retry")}
      </Button>
    </section>
  );
}
