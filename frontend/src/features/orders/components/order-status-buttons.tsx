import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

/** 下書きの注文の確定と取消。 */
export function OrderStatusButtons({
  onConfirm,
  onCancel,
}: {
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();

  return (
    <div className="flex flex-wrap gap-2">
      <Button type="button" onClick={onConfirm}>
        {t("orders.detail.confirm")}
      </Button>
      <Button type="button" variant="destructive" onClick={onCancel}>
        {t("orders.detail.cancel")}
      </Button>
    </div>
  );
}
