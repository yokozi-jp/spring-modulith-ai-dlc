import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { StatusScreen } from "@/components/status-screen";

// 戻り先を消さない。もう一度ログインしたら、ログインの前に開いていた画面へ戻る。
function LoginFailedPage() {
  const { t } = useTranslation();

  return (
    <StatusScreen heading={t("loginFailedHeading")} description={t("loginFailedDescription")} />
  );
}

export const Route = createFileRoute("/login-failed")({ component: LoginFailedPage });
