import { createInstance } from "i18next";

import en from "./locales/en.json";
import ja from "./locales/ja.json";
import { defaultLocale, resolveLocale, supportedLocales } from "./resolve-locale.ts";

// global instance を使わず、I18nextProvider で渡す instance を明示する。
export const i18n = createInstance();

i18n.on("languageChanged", (language) => {
  document.documentElement.lang = language;
});

// top-level await により、この module を import する側は init の完了後に評価される。
// resources を同梱するため、init 自体も同期的に完了する。
await i18n.init({
  resources: {
    ja: { translation: ja },
    // 既定言語と同じ message key を持つことを型で検査する。
    en: { translation: en satisfies typeof ja },
  },
  lng: resolveLocale(navigator.languages),
  fallbackLng: defaultLocale,
  supportedLngs: supportedLocales,
  // React が描画時にエスケープするため、i18next では二重にエスケープしない。
  interpolation: { escapeValue: false },
});
