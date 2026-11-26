import { ApiProblemError, isTenantNotSelected } from "@frontend/api/api-error";
import { Button, buttonVariants } from "@frontend/ui/button";
import { useQueryErrorResetBoundary } from "@tanstack/react-query";
import { Link, useRouter } from "@tanstack/react-router";
import type { ErrorComponentProps } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

// error.message は内部情報を含みうるため表示せず、カタログの文言だけを出す。
export function RouteError({ error }: ErrorComponentProps) {
  const { t } = useTranslation();
  const { reset } = useQueryErrorResetBoundary();
  const router = useRouter();

  // 所属を外されたときなどは、再試行しても同じ 403 になるため、テナントを選び直す画面へ案内する。
  if (isTenantNotSelected(error)) {
    return (
      <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10" role="alert">
        <h1 className="text-2xl font-semibold">{error.problem?.title ?? t("errorHeading")}</h1>
        <p className="text-muted-foreground">{t("tenancy.notSelectedDescription")}</p>
        <Link to="/tenants" className={buttonVariants()}>
          {t("tenancy.chooseTenant")}
        </Link>
      </section>
    );
  }

  // 権限不足の 403 は、再試行しても変わらない。役割の構成が変わりうるため、必要な役割は書かない。
  if (error instanceof ApiProblemError && error.status === 403) {
    return (
      <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10" role="alert">
        <h1 className="text-2xl font-semibold">{t("forbiddenHeading")}</h1>
        <p className="text-muted-foreground">{t("forbiddenDescription")}</p>
        <Link to="/" className={buttonVariants({ variant: "outline" })}>
          {t("backToHome")}
        </Link>
      </section>
    );
  }

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
