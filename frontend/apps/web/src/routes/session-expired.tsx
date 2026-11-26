import { createFileRoute } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { StatusScreen } from "@/components/status-screen";

// 戻り先を消さない。もう一度ログインしたら、有効期限が切れる前に開いていた画面へ戻る。
function SessionExpiredPage() {
  const { t } = useTranslation();

  return (
    <StatusScreen
      heading={t("sessionExpiredHeading")}
      description={t("sessionExpiredDescription")}
    />
  );
}

export const Route = createFileRoute("/session-expired")({ component: SessionExpiredPage });
