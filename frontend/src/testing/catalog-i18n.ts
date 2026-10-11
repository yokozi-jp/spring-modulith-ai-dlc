import { createInstance } from "i18next";

import ja from "@/i18n/locales/ja.json";

// @/i18n は document を使うため、Node のテストでは catalog だけを読んだ instance を作る。
const i18n = createInstance();
await i18n.init({ lng: "ja", resources: { ja: { translation: ja } } });

export const { t } = i18n;
