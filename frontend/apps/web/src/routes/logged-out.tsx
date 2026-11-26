import { clearReturnTo } from "@frontend/api/login-return-to";
import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { StatusScreen } from "@/components/status-screen";

function LoggedOutPage() {
  const { t } = useTranslation();

  return (
    <StatusScreen
      heading={t("loggedOutHeading")}
      actionLabel={t("loginAgain")}
      actionHref="/oauth2/authorization/web"
    />
  );
}

// 利用者をまたいで戻り先を残さない。
function beforeLoad() {
  clearReturnTo();
}

export const Route = createFileRoute("/logged-out")({ beforeLoad, component: LoggedOutPage });
