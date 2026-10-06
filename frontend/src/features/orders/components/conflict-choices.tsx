import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

/** 409 の後の 2 つの選択肢。live region の外（直後）に置き、読み上げに button の名前を混ぜない。 */
export function ConflictChoices({
  onDiscard,
  onReapply,
}: {
  onDiscard: () => void;
  onReapply: () => void;
}) {
  const { t } = useTranslation();

  return (
    <div className="flex flex-wrap gap-2">
      <Button type="button" variant="outline" onClick={onDiscard}>
        {t("orders.detail.discardChanges")}
      </Button>
      <Button type="button" onClick={onReapply}>
        {t("orders.detail.reapplyChanges")}
      </Button>
    </div>
  );
}
