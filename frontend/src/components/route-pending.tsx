import { useTranslation } from "react-i18next";

export function RoutePending() {
  const { t } = useTranslation();

  return (
    <output className="block px-6 py-16 text-muted-foreground sm:px-10">{t("loading")}</output>
  );
}
